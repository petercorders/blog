package com.petercoders.blog.tx

import org.springframework.aop.framework.AopProxyUtils
import org.springframework.aop.support.AopUtils
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.tx"])
class TxLabApp


// rollbackOn = ALL_EXCEPTIONS (6.2~) 를 켠 설정. Boot 의 자동 구성은
// @ConditionalOnMissingBean(AbstractTransactionManagementConfiguration) 이라 이게 있으면 물러난다.
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Profile("allexc")   // 이 프로파일에서만 뜬다 — 안 그러면 첫 컨텍스트까지 켜진다
@org.springframework.transaction.annotation.EnableTransactionManagement(
    rollbackOn = org.springframework.transaction.annotation.RollbackOn.ALL_EXCEPTIONS)
class AllExceptionsRollbackConfig

private fun boot(vararg extra: String): ConfigurableApplicationContext =
    SpringApplicationBuilder(TxLabApp::class.java)
        .properties(
            "spring.main.web-application-type=none",
            "spring.main.banner-mode=off",
            "spring.datasource.url=jdbc:h2:mem:txlab;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.main.allow-circular-references=true",
            "logging.level.root=WARN",
            "logging.level.org.springframework.aop.framework.CglibAopProxy=DEBUG",
            *extra,
        )
        .run()

private fun schema(jdbc: JdbcTemplate) {
    jdbc.execute("drop table if exists tx_log")
    jdbc.execute("create table tx_log(id identity primary key, tag varchar(40), note varchar(40))")
}

private fun rows(jdbc: JdbcTemplate, tag: String): Int =
    jdbc.queryForObject("select count(*) from tx_log where tag = ?", Int::class.java, tag) ?: -1

private fun row(h: Hit) =
    "| %-34s | %-8s | %-8s | %-5s | %s |".format(
        h.case, h.expected, h.active, h.expected == h.active, h.txName?.substringAfterLast('.') ?: "-")

fun main() {
    // ---------------------------------------------------------- E1 프록시 종류
    println("## [E1] proxy kind — spring.aop.proxy-target-class 기본값")
    boot().use { ctx -> printProxyKinds(ctx) }
    println()
    println("## [E1b] proxy kind — spring.aop.proxy-target-class=false")
    boot("spring.aop.proxy-target-class=false").use { ctx -> printProxyKinds(ctx) }

    val ctx = boot()
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    schema(jdbc)

    // ---------------------------------------------------------- E2 미통과 경로
    val p = ctx.getBean(BypassProbe::class.java)
    println()
    println("## [E2] which calls reach TransactionInterceptor")
    println("| case | expected | actual | match | tx name |")
    println("|---|---|---|---|---|")
    p.lifecycleHits().forEach { println(row(it)) }
    listOf(p.tx(), p.selfInvoke(), p.viaSelfField(), p.callPrivate(), p.callProtected(), p.finalTx())
        .forEach { println(row(it)) }

    println()
    println("## [E2b] final method on a CGLIB proxy reads a constructor-injected field")
    println("proxy class      = ${p.javaClass.name}")
    println("target class     = ${AopProxyUtils.ultimateTargetClass(p).name}")
    println("finalTx modifier = " +
        Modifier.toString(AopProxyUtils.ultimateTargetClass(p).getDeclaredMethod("finalTx").modifiers))
    println(
        try { "finalReadsField() -> " + p.finalReadsField() }
        catch (e: Throwable) { "finalReadsField() -> ${e.javaClass.name}: ${e.message}" }
    )

    println("internalAutoProxyCreator = " +
        ctx.getBean("org.springframework.aop.config.internalAutoProxyCreator").javaClass.name)

    // ---------------------------------------------------------- E2c protected 가 왜 열렸나
    println()
    println("## [E2c] protected 의 트랜잭션 속성을 직접 조회 — publicMethodsOnly 게이트 추적")
    val target = AopProxyUtils.ultimateTargetClass(p)
    val mProtected = target.getDeclaredMethod("protectedTx")
    val mPrivate = target.getDeclaredMethod("privateTx")
    println("protectedTx modifiers = ${Modifier.toString(mProtected.modifiers)}")
    println("privateTx   modifiers = ${Modifier.toString(mPrivate.modifiers)}")
    val tasBeans = ctx.getBeansOfType(org.springframework.transaction.interceptor.TransactionAttributeSource::class.java)
    println("TransactionAttributeSource beans = ${tasBeans.map { "${it.key}=${it.value.javaClass.name}" }}")
    tasBeans.values.forEach { tas ->
        val pmo = generateSequence(tas.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { c -> c.declaredFields.firstOrNull { it.name == "publicMethodsOnly" } }
            .firstOrNull()?.also { it.isAccessible = true }?.getBoolean(tas)
        println("  ${tas.javaClass.simpleName}: publicMethodsOnly = $pmo")
        println("    getTransactionAttribute(protectedTx) = ${tas.getTransactionAttribute(mProtected, target)}")
        println("    getTransactionAttribute(privateTx)   = ${tas.getTransactionAttribute(mPrivate, target)}")
    }
    println("CGLIB 프록시의 오버라이드 = " +
        Modifier.toString(p.javaClass.getDeclaredMethod("protectedTx").modifiers))

    // ---------------------------------------------------------- E3 REQUIRES_NEW 자기 호출
    val n = ctx.getBean(NestedProbe::class.java)
    println()
    println("## [E3] REQUIRES_NEW: self-invocation vs via proxy (rows left in H2)")
    println("| path | returned | rows committed |")
    println("|---|---|---|")
    println("| self-invocation   | ${n.outerSelfInvoke()} | ${rows(jdbc, "e3-self")} |")
    println("| self field(proxy) | ${n.outerViaProxy()} | ${rows(jdbc, "e3-proxy")} |")
    val req = try { n.outerRequiredViaProxy() } catch (e: UnexpectedRollbackException) { "threw ${e.javaClass.simpleName}" }
    println("| REQUIRED(proxy)   | $req | ${rows(jdbc, "e3-required")} |")

    // ---------------------------------------------------------- E4 ThreadLocal 슬롯
    println()
    println("## [E4] TransactionSynchronizationManager ThreadLocal slots")
    val tm = ctx.getBean(PlatformTransactionManager::class.java)
    println("--- outside a transaction (main thread)")
    dumpSlots()
    TransactionTemplate(tm).execute {
        println("--- inside a transaction (main thread)")
        dumpSlots()
        val t = Thread { println("--- same moment, another thread"); dumpSlots() }
        t.start(); t.join()
    }

    // ---------------------------------------------------------- E4b bindResource 이중 바인딩
    println()
    println("## [E4b] TransactionSynchronizationManager.bindResource — same key twice")
    TSM.bindResource("probe-key", "first")
    try {
        TSM.bindResource("probe-key", "second")
    } catch (e: IllegalStateException) {
        println("bindResource(\"probe-key\", \"second\") -> ${e.javaClass.simpleName}: ${e.message}")
    }
    TSM.unbindResource("probe-key")

    // ---------------------------------------------------------- E5 롤백 규칙
    val r = ctx.getBean(RollbackProbe::class.java)
    println()
    println("## [E5] rollback rules (rows left in H2: 0 = rolled back, 1 = committed)")
    println("| thrown | attribute | rows |")
    println("|---|---|---|")
    fun run(tag: String, label: String, attr: String, f: (String) -> Unit) {
        try { f(tag) } catch (_: Throwable) {}
        println("| $label | $attr | ${rows(jdbc, tag)} |")
    }
    run("e5-a", "IllegalStateException", "(none)") { r.unchecked(it) }
    run("e5-b", "DomainFailure : Exception", "(none)") { r.checkedLike(it) }
    run("e5-c", "DomainFailure : Exception", "rollbackFor=Exception") { r.checkedLikeWithRule(it) }
    run("e5-d", "IllegalStateException", "rollbackFor=Exception, noRollbackFor=ISE") { r.depthRule(it) }
    ctx.close()

    // -------------------------------------------------- E5b 전역 스위치 rollbackOn=ALL_EXCEPTIONS
    println()
    println("## [E5b] @EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS) — 같은 코드, 전역 설정만 다름")
    val ctx2 = SpringApplicationBuilder(TxLabApp::class.java, AllExceptionsRollbackConfig::class.java)
        .properties(
            "spring.main.web-application-type=none", "spring.main.banner-mode=off",
            "spring.datasource.url=jdbc:h2:mem:txlab;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.main.allow-circular-references=true", "logging.level.root=WARN",
            "spring.profiles.active=allexc",
        ).run()
    val jdbc2 = ctx2.getBean(JdbcTemplate::class.java)
    val r2 = ctx2.getBean(RollbackProbe::class.java)
    println("| thrown | attribute | rows |")
    println("|---|---|---|")
    try { r2.checkedLike("e5b-a") } catch (_: Throwable) {}
    println("| DomainFailure : Exception | (none) + rollbackOn=ALL_EXCEPTIONS | ${rows(jdbc2, "e5b-a")} |")
    ctx2.close()
    exitProcess(0)
}

private fun printProxyKinds(ctx: ConfigurableApplicationContext) {
    // JDK 동적 프록시면 빈이 OrderService 타입이 아니므로 이름으로 꺼낸다.
    listOf("orderService", "plainService").forEach { name ->
        val b = ctx.getBean(name)
        println(
            "%-13s aop=%-5s cglib=%-5s jdk=%-5s %s".format(
                name, AopUtils.isAopProxy(b), AopUtils.isCglibProxy(b),
                AopUtils.isJdkDynamicProxy(b), b.javaClass.name))
    }
}

/** private static ThreadLocal 필드 6개를 그대로 읽는다. */
private fun dumpSlots() {
    TSM::class.java.declaredFields
        .filter { ThreadLocal::class.java.isAssignableFrom(it.type) }
        .forEach {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            println("  %-32s = %s".format(it.name, (it.get(null) as ThreadLocal<*>).get()))
        }
}
