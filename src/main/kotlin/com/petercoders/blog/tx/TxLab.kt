package com.petercoders.blog.tx

import org.springframework.aop.framework.AopProxyUtils
import org.springframework.aop.support.AopUtils
import org.springframework.boot.SpringBootVersion
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.SpringVersion
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute
import org.springframework.transaction.interceptor.RollbackRuleAttribute
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.Modifier
import java.util.concurrent.CyclicBarrier
import kotlin.system.exitProcess
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM

// SPRING-INTERNALS 1편(@Transactional) 실험 러너. ./gradlew --offline -q txLab
// 출력 구간 마커 = 로그 파일 id: [e0] 런타임, [e1] Proxy 종류, [e2] 우회 경로, [e4] final NPE,
// [e5] 전파, [e6] Thread, [e7] 롤백 규칙, [e10] TransactionExecutionListener, [e12] readOnly 라우팅.
// e3(javap)·e8(로그 레벨, txLogLab)·e9(Advisor 순서, txOrderLab)·e11(Coroutine, txCoLab)은 따로 돈다.

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.tx"])
class TxLabApp


// rollbackOn = ALL_EXCEPTIONS (6.2~) 를 켠 설정. Boot 의 자동 구성은
// @ConditionalOnMissingBean(AbstractTransactionManagementConfiguration) 이라 이게 있으면 물러난다.
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Profile("allexc")   // 이 프로파일에서만 뜬다 — 안 그러면 첫 컨텍스트까지 켜진다
@org.springframework.transaction.annotation.EnableTransactionManagement(
    rollbackOn = org.springframework.transaction.annotation.RollbackOn.ALL_EXCEPTIONS)
class AllExceptionsRollbackConfig

private val BASE = arrayOf(
    "spring.main.web-application-type=none",
    "spring.main.banner-mode=off",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "logging.level.root=WARN",
    "logging.level.org.springframework.aop.framework.CglibAopProxy=DEBUG",
)

private fun boot(vararg extra: String): ConfigurableApplicationContext =
    SpringApplicationBuilder(TxLabApp::class.java)
        .properties(
            *BASE,
            "spring.datasource.url=jdbc:h2:mem:txlab;DB_CLOSE_DELAY=-1",
            "spring.main.allow-circular-references=true",
            *extra,
        )
        .run()

/** 부팅이 실패할 수 있는 컨텍스트. 실패하면 최상위 예외와 root cause 첫 줄을 돌려준다. */
private fun tryBoot(vararg extra: String): Pair<ConfigurableApplicationContext?, String> =
    try {
        SpringApplicationBuilder(TxLabApp::class.java)
            .properties(
                *BASE,
                "spring.datasource.url=jdbc:h2:mem:txlab;DB_CLOSE_DELAY=-1",
                // 실패 보고서(FailureAnalyzer)와 스택은 줄이고 예외는 직접 찍는다
                "logging.level.org.springframework.boot.SpringApplication=OFF",
                "logging.level.org.springframework.boot.diagnostics=OFF",
                *extra,
            ).run() to "booted"
    } catch (e: Throwable) {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        null to ("boot failed: ${e.javaClass.name}\n    root cause: ${root.javaClass.name}: " +
            (root.message?.lineSequence()?.first() ?: ""))
    }

private fun schema(jdbc: JdbcTemplate) {
    jdbc.execute("drop table if exists tx_log")
    jdbc.execute("create table tx_log(id identity primary key, tag varchar(40), note varchar(40))")
    jdbc.execute("drop table if exists tx_kv")
    jdbc.execute("create table tx_kv(k varchar(40) primary key, v int)")
}

private fun rows(jdbc: JdbcTemplate, tag: String): Int =
    jdbc.queryForObject("select count(*) from tx_log where tag = ?", Int::class.java, tag) ?: -1

private fun row(h: Hit) =
    "| %-40s | %-6s | %s |".format(h.case, h.active, h.txName?.substringAfterLast('.') ?: "-")

private fun firstLine(e: Throwable) = "${e.javaClass.name}: ${e.message?.lineSequence()?.first()}"

private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

fun main() {
    printRuntime()

    // ---------------------------------------------------------- [e1] Proxy 종류
    println()
    println("## [e1] proxy kind — spring.aop.proxy-target-class 기본값")
    boot().use { ctx ->
        printProxyKinds(ctx)
        println("internalAutoProxyCreator = " +
            ctx.getBean("org.springframework.aop.config.internalAutoProxyCreator").javaClass.name)
        println("Boot TransactionAutoConfiguration\$EnableTransactionManagementConfiguration beans present = " +
            ctx.beanDefinitionNames.filter { it.contains("TransactionAutoConfiguration\$EnableTransactionManagementConfiguration") })
        println("Boot AopAutoConfiguration beans present = " + ctx.beanDefinitionNames.filter { it.contains("AopAutoConfiguration") })
    }
    println()
    println("## [e1b] proxy kind — spring.aop.proxy-target-class=false")
    boot("spring.aop.proxy-target-class=false").use { ctx ->
        printProxyKinds(ctx)

        // ------------------------------------------------------ [e1c] false 의 대가와 @Proxyable
        println()
        println("## [e1c] proxy-target-class=false 의 대가 — 구현 클래스 타입 조회, @Proxyable")
        println("ctx.getBean(OrderService::class.java) -> " +
            try { ctx.getBean(OrderService::class.java).javaClass.name } catch (e: Throwable) { firstLine(e) })
        println("ctx.getBean(OrderPort::class.java)    -> " + ctx.getBean(OrderPort::class.java).javaClass.name)
        val ship = ctx.getBean("shipService")
        println("shipService (@Proxyable(TARGET_CLASS), implements ShipPort): cglib=${AopUtils.isCglibProxy(ship)} " +
            "jdk=${AopUtils.isJdkDynamicProxy(ship)} ${ship.javaClass.name}")
        println("shipService.ship() -> active=${(ship as ShipPort).ship().active}")
    }
    val (jdkInjectCtx, jdkInjectResult) = tryBoot(
        "spring.main.allow-circular-references=true",
        "spring.aop.proxy-target-class=false", "spring.profiles.active=jdkinject")
    println("constructor(orderService: OrderService) with proxy-target-class=false -> $jdkInjectResult")
    jdkInjectCtx?.close()

    val ctx = boot()
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    schema(jdbc)

    // ---------------------------------------------------------- [e2] 우회 경로
    val p = ctx.getBean(BypassProbe::class.java)
    println()
    println("## [e2] which calls reach TransactionInterceptor (isActualTransactionActive inside the method)")
    println("| case | actual | tx name |")
    println("|---|---|---|")
    p.lifecycleHits().forEach { println(row(it)) }
    listOf(p.tx(), p.selfInvoke(), p.viaSelfField(), p.callPrivate(), p.callProtected(), p.finalTx())
        .forEach { println(row(it)) }

    // ---------------------------------------------------------- [e2c] protected 가 왜 열렸나
    println()
    println("## [e2c] protected 의 트랜잭션 속성을 직접 조회 — publicMethodsOnly 게이트 추적")
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

    // ---------------------------------------------------------- [e2f] package-private (Java)
    println()
    println("## [e2f] package-private @Transactional (Java class, called from another bean in the same package)")
    val pkg = ctx.getBean(PkgPrivateCaller::class.java)
    println("PkgPrivateProbe proxy class = ${pkg.proxyClass()}")
    println("PkgPrivateCaller.call() -> probe.pkgTx() : ${pkg.call()}")

    // ---------------------------------------------------------- [e4] final 메서드 NPE
    println()
    println("## [e4] final method on a CGLIB proxy reads a constructor-injected field")
    println("proxy class      = ${p.javaClass.name}")
    println("target class     = ${AopProxyUtils.ultimateTargetClass(p).name}")
    println("finalTx modifier = " +
        Modifier.toString(AopProxyUtils.ultimateTargetClass(p).getDeclaredMethod("finalTx").modifiers))
    println(
        try { "finalReadsField() -> " + p.finalReadsField() }
        catch (e: Throwable) { "finalReadsField() -> ${e.javaClass.name}: ${e.message}" }
    )

    // ---------------------------------------------------------- [e5] REQUIRES_NEW 자기 호출 · REQUIRED
    val n = ctx.getBean(NestedProbe::class.java)
    println()
    println("## [e5] REQUIRES_NEW: self-invocation vs via proxy, REQUIRED (rows left in H2)")
    println("| path | returned | rows committed |")
    println("|---|---|---|")
    println("| self-invocation   | ${n.outerSelfInvoke()} | ${rows(jdbc, "e3-self")} |")
    println("| self field(proxy) | ${n.outerViaProxy()} | ${rows(jdbc, "e3-proxy")} |")
    val req = try { n.outerRequiredViaProxy() } catch (e: UnexpectedRollbackException) { "threw ${e.javaClass.simpleName}" }
    println("| REQUIRED(proxy)   | $req | ${rows(jdbc, "e3-required")} |")

    // ---------------------------------------------------------- [e5b] rollback-only 표시 조건 · NESTED · upsert
    println()
    println("## [e5b] rollback-only marker conditions, NESTED, duplicate-key upsert (rows left in H2)")
    println("| case | returned / thrown | rows committed |")
    println("|---|---|---|")
    fun attempt(f: () -> String): String =
        try { f() } catch (e: Throwable) { "threw ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.first()}" }
    println("| REQUIRED(proxy) + InnerFailed : RuntimeException | " +
        "${attempt { n.outerRequiredViaProxyTagged("e5b-req-rt") }} | ${rows(jdbc, "e5b-req-rt")} |")
    println("| REQUIRED(proxy) + DomainFailure : Exception | " +
        "${attempt { n.outerRequiredCheckedViaProxy("e5b-req-checked") }} | ${rows(jdbc, "e5b-req-checked")} |")
    println("| NESTED(proxy) + InnerFailed : RuntimeException | " +
        "${attempt { n.outerNestedViaProxy("e5b-nested") }} | ${rows(jdbc, "e5b-nested")} |")
    jdbc.update("insert into tx_kv(k, v) values ('dup', 1)")
    val upsert = ctx.getBean(KvUpsertService::class.java)
    println("| upsert: other bean @Transactional insert -> duplicate key -> catch -> update | " +
        "${attempt { upsert.upsert("dup", 2) }} | v=${jdbc.queryForObject("select v from tx_kv where k = 'dup'", Int::class.java)} (was 1) |")
    println("upsert caught inside: ${upsert.lastCaught}")
    val tmMain = ctx.getBean(PlatformTransactionManager::class.java)
    println("transactionManager = ${tmMain.javaClass.name}, globalRollbackOnParticipationFailure = " +
        "${(tmMain as AbstractPlatformTransactionManager).isGlobalRollbackOnParticipationFailure}, " +
        "nestedTransactionAllowed = ${tmMain.isNestedTransactionAllowed}")

    // ---------------------------------------------------------- [e6] ThreadLocal 슬롯
    println()
    println("## [e6] TransactionSynchronizationManager ThreadLocal slots")
    val tm = ctx.getBean(PlatformTransactionManager::class.java)
    println("--- outside a transaction (main thread)")
    dumpSlots()
    TransactionTemplate(tm).execute {
        println("--- inside a transaction (main thread)")
        dumpSlots()
        val t = Thread { println("--- same moment, another thread"); dumpSlots() }
        t.start(); t.join()
    }

    // ---------------------------------------------------------- [e6b] bindResource 이중 바인딩
    println()
    println("## [e6b] TransactionSynchronizationManager.bindResource — same key twice")
    TSM.bindResource("probe-key", "first")
    try {
        TSM.bindResource("probe-key", "second")
    } catch (e: IllegalStateException) {
        println("bindResource(\"probe-key\", \"second\") -> ${e.javaClass.simpleName}: ${e.message}")
    }
    TSM.unbindResource("probe-key")

    // ---------------------------------------------------------- [e7] 롤백 규칙
    val r = ctx.getBean(RollbackProbe::class.java)
    println()
    println("## [e7] rollback rules (rows left in H2: 0 = rolled back, 1 = committed)")
    println("| thrown | attribute | rows |")
    println("|---|---|---|")
    fun runRule(tag: String, label: String, attr: String, f: (String) -> Unit) {
        try { f(tag) } catch (_: Throwable) {}
        println("| $label | $attr | ${rows(jdbc, tag)} |")
    }
    runRule("e5-a", "IllegalStateException", "(none)") { r.unchecked(it) }
    runRule("e5-b", "DomainFailure : Exception", "(none)") { r.checkedLike(it) }
    runRule("e5-c", "DomainFailure : Exception", "rollbackFor=Exception") { r.checkedLikeWithRule(it) }
    runRule("e5-d", "IllegalStateException", "rollbackFor=Exception, noRollbackFor=ISE") { r.depthRule(it) }

    // ---------------------------------------------------------- [e7c] getDepth · 문자열 규칙
    println()
    println("## [e7c] RollbackRuleAttribute.getDepth — class rules, unmatched, string patterns")
    val ise = IllegalStateException("x")
    println("RollbackRuleAttribute(Exception::class).getDepth(IllegalStateException)          = " +
        RollbackRuleAttribute(Exception::class.java).getDepth(ise))
    println("NoRollbackRuleAttribute(IllegalStateException::class).getDepth(IllegalStateException) = " +
        NoRollbackRuleAttribute(IllegalStateException::class.java).getDepth(ise))
    println("RollbackRuleAttribute(java.io.IOException::class).getDepth(IllegalStateException)  = " +
        RollbackRuleAttribute(java.io.IOException::class.java).getDepth(ise))
    val rule = RuleBasedTransactionAttribute().apply {
        rollbackRules = listOf(RollbackRuleAttribute(Exception::class.java), NoRollbackRuleAttribute(IllegalStateException::class.java))
    }
    println("rules [rollbackFor=Exception, noRollbackFor=ISE].rollbackOn(IllegalStateException)  = ${rule.rollbackOn(ise)}")
    println("RollbackRuleAttribute(\"Failure\").getDepth(DomainFailure)                           = " +
        RollbackRuleAttribute("Failure").getDepth(DomainFailure("x")))
    println("RollbackRuleAttribute(\"Failure\").getDepth(DataAccessResourceFailureException)      = " +
        RollbackRuleAttribute("Failure").getDepth(org.springframework.dao.DataAccessResourceFailureException("x")))
    println("RollbackRuleAttribute(\"java.util\").getDepth(ConcurrentModificationException)       = " +
        RollbackRuleAttribute("java.util").getDepth(java.util.ConcurrentModificationException()))
    println("RollbackRuleAttribute(\"java.util\").getDepth(NoSuchElementException)                = " +
        RollbackRuleAttribute("java.util").getDepth(java.util.NoSuchElementException()))

    // ---------------------------------------------------------- [e7d] Java 메서드의 checked 예외
    println()
    println("## [e7d] checked exception from a Java API inside a Kotlin @Transactional method (rows: 0 = rolled back, 1 = committed)")
    println("| proxy | method | caller received | rows |")
    println("|---|---|---|---|")
    fun caught(f: () -> Unit): String = try { f(); "no exception" } catch (e: Throwable) {
        e.javaClass.name + (e.cause?.let { " (cause ${it.javaClass.name})" } ?: "")
    }
    println("| CGLIB (${r.javaClass.simpleName}) | javaChecked (no @Throws) | ${caught { r.javaChecked("e7d-a") }} | ${rows(jdbc, "e7d-a")} |")
    println("| CGLIB (${r.javaClass.simpleName}) | javaCheckedThrows (@Throws(IOException)) | ${caught { r.javaCheckedThrows("e7d-b") }} | ${rows(jdbc, "e7d-b")} |")
    val cp = ctx.getBean(CheckedPort::class.java)
    println("| CGLIB (interface bean, ${cp.javaClass.simpleName}) | CheckedPort.javaChecked | ${caught { cp.javaChecked("e7d-c") }} | ${rows(jdbc, "e7d-c")} |")

    // ---------------------------------------------------------- [e10] TransactionExecutionListener
    val listener = ctx.getBean(CountingTxListener::class.java)
    println()
    println("## [e10] TransactionExecutionListener events per call (Boot auto-configured TM, listener is just a @Component)")
    println("listeners on TM = ${(tmMain as org.springframework.transaction.ConfigurableTransactionManager).transactionExecutionListeners.map { it.javaClass.simpleName }}")
    println("| call | events |")
    println("|---|---|")
    fun events(label: String, f: () -> Unit) {
        listener.events.clear()
        try { f() } catch (_: Throwable) {}
        println("| $label | ${listener.events.joinToString(", ").ifEmpty { "(none)" }} |")
    }
    events("p.tx() (external)") { p.tx() }
    events("p.selfInvoke()") { p.selfInvoke() }
    events("n.outerViaProxy() (REQUIRES_NEW inner fails)") { n.outerViaProxy() }
    events("n.outerRequiredViaProxy() (REQUIRED inner fails)") { n.outerRequiredViaProxy() }
    println()
    println("contrast: inside an outer TransactionTemplate(name=test-outer), as a @Transactional test would be")
    val tt = TransactionTemplate(tm).apply { setName("test-outer") }
    tt.executeWithoutResult {
        val a = p.tx(); val b = p.selfInvoke()
        println("| p.tx()         | active=${a.active} name=${a.txName} |")
        println("| p.selfInvoke() | active=${b.active} name=${b.txName} |")
        it.setRollbackOnly()
    }
    println("without the outer transaction: p.tx() name=${p.tx().txName}, p.selfInvoke() active=${p.selfInvoke().active} name=${p.selfInvoke().txName}")
    ctx.close()

    // ---------------------------------------------------------- [e5b] 참여 실패 플래그 끄기
    println()
    println("## [e5b] globalRollbackOnParticipationFailure=false via TransactionManagerCustomizer (profile noglobal)")
    boot("spring.profiles.active=noglobal").use { c ->
        val tmc = c.getBean(PlatformTransactionManager::class.java) as AbstractPlatformTransactionManager
        println("transactionManager = ${tmc.javaClass.name}, globalRollbackOnParticipationFailure = ${tmc.isGlobalRollbackOnParticipationFailure}")
        val j = c.getBean(JdbcTemplate::class.java)
        val nn = c.getBean(NestedProbe::class.java)
        val res = attempt { nn.outerRequiredViaProxyTagged("e5b-noglobal") }
        println("| REQUIRED(proxy) + InnerFailed, flag=false | $res | ${rows(j, "e5b-noglobal")} |")
        println("rows by note: " + j.queryForList("select note from tx_log where tag = 'e5b-noglobal' order by id", String::class.java))
    }

    // ---------------------------------------------------------- [e5c] 커넥션 수와 풀 교착
    println()
    println("## [e5c] connections held: REQUIRES_NEW vs NESTED, and pool size vs concurrent threads (connection-timeout=1000ms)")
    fun poolCtx(size: Int) = boot(
        "spring.datasource.url=jdbc:h2:mem:txpool$size;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=$size",
        "spring.datasource.hikari.minimum-idle=$size",
        "spring.datasource.hikari.connection-timeout=1000",
    ).also { schema(it.getBean(JdbcTemplate::class.java)) }
    poolCtx(3).use { c ->
        val pp = c.getBean(PoolProbe::class.java)
        println("(a) pool=3, one thread: ${pp.outerRequiresNew("e5c-a-rn")}")
        println("(a) pool=3, one thread: ${pp.outerNested("e5c-a-nested")}")
    }
    poolCtx(1).use { c ->
        val pp = c.getBean(PoolProbe::class.java)
        val t0 = System.nanoTime()
        val res = try { pp.outerRequiresNew("e5c-b") } catch (e: Throwable) {
            "threw ${firstLine(e)}" + (e.cause?.let { "\n    cause: ${firstLine(it)}" } ?: "")
        }
        println("(b) pool=1, one thread, REQUIRES_NEW after ${ms(t0)}ms -> $res")
        println("    rows left: ${rows(c.getBean(JdbcTemplate::class.java), "e5c-b")}")
    }
    for (size in listOf(2, 3)) poolCtx(size).use { c ->
        val pp = c.getBean(PoolProbe::class.java)
        val barrier = CyclicBarrier(2)
        val results = java.util.concurrent.ConcurrentHashMap<String, String>()
        val t0 = System.nanoTime()
        val threads = listOf("w1", "w2").map { name ->
            Thread({
                val s = System.nanoTime()
                val res = try { pp.outerThenBarrier("e5c-c$size", barrier) } catch (e: Throwable) {
                    "threw ${firstLine(e)}" + (e.cause?.let { " / cause: ${firstLine(it)}" } ?: "")
                }
                results[name] = "after ${ms(s)}ms -> $res"
            }, name)
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        println("(c) pool=$size, 2 threads each holding an outer connection then REQUIRES_NEW (total ${ms(t0)}ms):")
        results.toSortedMap().forEach { (k, v) -> println("    $k $v") }
        println("    rows left: ${rows(c.getBean(JdbcTemplate::class.java), "e5c-c$size")}")
    }

    // ---------------------------------------------------------- [e6c] Thread 넘기기 · [e6d] 락 대기
    println()
    println("## [e6c] writes handed to other threads inside an outer @Transactional that finally rolls back")
    boot("spring.profiles.active=async",
        "spring.datasource.url=jdbc:h2:mem:txasync;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000").use { c ->
        val j = c.getBean(JdbcTemplate::class.java)
        schema(j)
        val h = c.getBean(HandoffProbe::class.java)
        val outcome = try { h.handoffThenRollback(); "no exception" } catch (e: Throwable) { "caller got ${e.javaClass.simpleName}" }
        println("outer: $outcome")
        println("| path | thread | isActualTransactionActive | rows left after outer rollback |")
        println("|---|---|---|---|")
        val byPath = h.seen.groupBy { it.path }
        fun left(tag: String, note: String? = null) =
            if (note == null) rows(j, tag)
            else j.queryForObject("select count(*) from tx_log where tag = ? and note = ?", Int::class.java, tag, note.take(40))
        byPath["caller"]?.forEach { println("| caller | ${it.thread} | ${it.active} | ${left("e6c-caller")} |") }
        byPath["@Async"]?.forEach { println("| @Async | ${it.thread} | ${it.active} | ${left("e6c-async")} |") }
        byPath["CompletableFuture.supplyAsync"]?.forEach { println("| CompletableFuture.supplyAsync | ${it.thread} | ${it.active} | ${left("e6c-cf")} |") }
        byPath["parallelStream"]?.groupBy { it.thread to it.active }?.toSortedMap(compareBy({ it.first }, { it.second }))
            ?.forEach { (k, v) -> println("| parallelStream (${v.size} of 16) | ${k.first} | ${k.second} | ${left("e6c-par", k.first)} |") }
        byPath["new Thread"]?.forEach { println("| new Thread | ${it.thread} | ${it.active} | ${left("e6c-thread")} |") }
        println("parallelStream rows left in total: ${rows(j, "e6c-par")} of 16")

        println()
        println("## [e6d] outer updates a row, then a worker thread updates the same row (H2 LOCK_TIMEOUT=1000)")
        j.update("insert into tx_log(tag, note) values ('e6d', 'init')")
        val t0 = System.nanoTime()
        val res = h.lockThenHandoff()
        println("after ${ms(t0)}ms -> $res")
    }

    // ---------------------------------------------------------- [e7d] JDK Proxy 갈래
    println()
    println("## [e7d] (JDK Proxy) same interface bean with spring.aop.proxy-target-class=false")
    boot("spring.aop.proxy-target-class=false").use { c ->
        val j = c.getBean(JdbcTemplate::class.java)
        val port = c.getBean(CheckedPort::class.java)
        println("| JDK (${port.javaClass.name}) | CheckedPort.javaChecked | ${caught { port.javaChecked("e7d-d") }} | ${rows(j, "e7d-d")} |")
    }

    // ---------------------------------------------------------- [e7b] 전역 스위치 rollbackOn=ALL_EXCEPTIONS
    println()
    println("## [e7b] @EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS) — 같은 코드, 전역 설정만 다름")
    val ctx2 = SpringApplicationBuilder(TxLabApp::class.java, AllExceptionsRollbackConfig::class.java)
        .properties(
            *BASE,
            "spring.datasource.url=jdbc:h2:mem:txlab;DB_CLOSE_DELAY=-1",
            "spring.main.allow-circular-references=true",
            "spring.profiles.active=allexc",
        ).run()
    val jdbc2 = ctx2.getBean(JdbcTemplate::class.java)
    val r2 = ctx2.getBean(RollbackProbe::class.java)
    println("| thrown | attribute | rows |")
    println("|---|---|---|")
    try { r2.checkedLike("e7b-a") } catch (_: Throwable) {}
    println("| DomainFailure : Exception | (none) + rollbackOn=ALL_EXCEPTIONS | ${rows(jdbc2, "e7b-a")} |")
    println("proxy kinds with a user-declared @EnableTransactionManagement (proxyTargetClass attribute left at default):")
    printProxyKinds(ctx2)
    println("internalAutoProxyCreator = " +
        ctx2.getBean("org.springframework.aop.config.internalAutoProxyCreator").javaClass.name)
    println("AbstractTransactionManagementConfiguration beans = " +
        ctx2.getBeanNamesForType(org.springframework.transaction.annotation.AbstractTransactionManagementConfiguration::class.java).toList())
    println("Boot TransactionAutoConfiguration\$EnableTransactionManagementConfiguration beans present = " +
        ctx2.beanDefinitionNames.filter { it.contains("TransactionAutoConfiguration\$EnableTransactionManagementConfiguration") })
    println("Boot AopAutoConfiguration beans present = " + ctx2.beanDefinitionNames.filter { it.contains("AopAutoConfiguration") })
    println("internalAutoProxyCreator.proxyTargetClass = " +
        (ctx2.getBean("org.springframework.aop.config.internalAutoProxyCreator") as org.springframework.aop.framework.ProxyConfig).isProxyTargetClass)

    // ---------------------------------------------------------- [e7e] ALL_EXCEPTIONS 위의 noRollbackFor
    println()
    println("## [e7e] ALL_EXCEPTIONS + method-level noRollbackFor = [DomainFailure::class]")
    try { r2.checkedLikeNoRollback("e7e") } catch (_: Throwable) {}
    println("| DomainFailure : Exception | noRollbackFor=DomainFailure + rollbackOn=ALL_EXCEPTIONS | ${rows(jdbc2, "e7e")} |")
    ctx2.close()

    // ---------------------------------------------------------- [e2d] 순환 참조를 끈 self 주입
    println()
    println("## [e2d] self injection with spring.main.allow-circular-references=false")
    run {
        val (c, res) = tryBoot("spring.main.allow-circular-references=false", "spring.profiles.active=circ-off,circ-field")
        println("(a) @Autowired lateinit var self -> $res")
        c?.use { println("    viaSelf: ${row(it.getBean(SelfFieldProbe::class.java).viaSelf())}") }
    }
    run {
        val (c, res) = tryBoot("spring.main.allow-circular-references=false", "spring.profiles.active=circ-off,circ-lazy")
        println("(b) @Autowired @Lazy lateinit var self -> $res")
        c?.use { println("    ${row(it.getBean(LazySelfProbe::class.java).viaSelf())}") }
    }
    run {
        val (c, res) = tryBoot("spring.main.allow-circular-references=false",
            "spring.profiles.active=circ-off,circ-provider,pc-provider")
        println("(c) constructor ObjectProvider<Self> -> $res")
        c?.use {
            println("    ${row(it.getBean(ProviderSelfProbe::class.java).viaSelf())}")

            // ---------------------------------------------- [e2g] (2) @PostConstruct + ObjectProvider, 순환 참조 금지
            println()
            println("## [e2g] @PostConstruct calling through the proxy")
            println("(2) allow-circular-references=false, @PostConstruct → ObjectProvider.getObject().tx() -> " +
                it.getBean(PostConstructProviderProbe::class.java).atInit)
        }
    }

    // ---------------------------------------------------------- [e2e]·[e2g] exposeProxy, @PostConstruct + early Proxy
    boot("spring.profiles.active=expose,pc-self").use { c ->
        val pc = c.getBean(PostConstructSelfProbe::class.java)
        println("(1) allow-circular-references=true, @PostConstruct → self.tx() -> ${row(pc.atInit!!)}")
        println("    self is ${pc.self.javaClass.name}")
        println()
        println("## [e2e] exposeProxy = true + AopContext.currentProxy()")
        val e = c.getBean(ExposeProbe::class.java)
        println("internalAutoProxyCreator.exposeProxy = " +
            (c.getBean("org.springframework.aop.config.internalAutoProxyCreator") as org.springframework.aop.framework.ProxyConfig).isExposeProxy)
        println(row(e.viaCurrentProxy()))
        println(row(e.selfInvoke()))
    }

    // ---------------------------------------------------------- [e12] readOnly 라우팅
    println()
    println("## [e12] readOnly routing: which H2 database did a @Transactional(readOnly = true) method actually use")
    println("| DataSource given to the TransactionManager | readOnly=true method | readOnly=false method |")
    println("|---|---|---|")
    for ((variant, label) in listOf(
        "routing-a" to "(a) AbstractRoutingDataSource (key = isCurrentTransactionReadOnly)",
        "routing-b" to "(b) LazyConnectionDataSourceProxy(routing DataSource)",
        "routing-c" to "(c) LazyConnectionDataSourceProxy(writer) + setReadOnlyDataSource(reader)",
    )) boot("spring.profiles.active=routing,$variant").use { c ->
        val rp = c.getBean(RoutingProbe::class.java)
        println("| $label | ${attempt { rp.readOnlyDb() }} | ${attempt { rp.writeDb() }} |")
    }
    exitProcess(0)
}

private fun printRuntime() {
    println("## [e0] runtime versions (printed by this JVM)")
    println("java.runtime.version = ${System.getProperty("java.runtime.version")}")
    println("java.vendor          = ${System.getProperty("java.vendor")} / ${System.getProperty("java.vm.name")}")
    println("java.home            = ${System.getProperty("java.home")}")
    println("Spring Framework     = ${SpringVersion.getVersion()}")
    println("Spring Boot          = ${SpringBootVersion.getVersion()}")
    println("Kotlin stdlib        = ${KotlinVersion.CURRENT}")
    println("HikariCP             = ${implVersion("com.zaxxer.hikari.HikariDataSource", "META-INF/maven/com.zaxxer/HikariCP/pom.properties")}")
    println("H2                   = ${implVersion("org.h2.Driver", "META-INF/maven/com.h2database/h2/pom.properties")}")
}

private fun implVersion(className: String, pom: String): String {
    val c = Class.forName(className)
    c.`package`.implementationVersion?.let { return it }
    val props = java.util.Properties()
    c.classLoader.getResourceAsStream(pom)?.use { props.load(it) }
    return props.getProperty("version") ?: "unknown"
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
