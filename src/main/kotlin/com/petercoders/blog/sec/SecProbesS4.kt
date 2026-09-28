// SPRING-INTERNALS 3편(절 3) — [S4]/[S4b] 실행기.
// [S4] 기본 order(@PostAuthorize=500 바깥, @Transactional=LOWEST_PRECEDENCE 안쪽)에서
//      OrderProbe.withdraw() 를 거부되도록 불러 sec_log 행이 남는지 잰다.
// [S4b] TxOuterConfig(@EnableTransactionManagement(order=0)) 프로파일 txouter 로
//      새 컨텍스트를 띄우고 글자 그대로 같은 호출을 반복한다 — order 만 뒤집혔다.
// 실행: cd ~/Desktop/development/blog && ./gradlew --offline -q secLab --args='S4'
//      cd ~/Desktop/development/blog && ./gradlew --offline -q secLab --args='S4b'
package com.petercoders.blog.sec

import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.Ordered
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

private fun schemaSec(jdbc: JdbcTemplate) {
    jdbc.execute("drop table if exists sec_log")
    jdbc.execute("create table sec_log(id identity primary key, tag varchar(40), note varchar(40))")
}

private fun rowsSec(jdbc: JdbcTemplate, tag: String): Int =
    jdbc.queryForObject("select count(*) from sec_log where tag = ?", Int::class.java, tag) ?: -1

/** authentication.name="alice" != ownerId="bob" 이므로 @PostAuthorize 가 반드시 거부한다. */
private fun authenticateAsAlice() {
    val auth = TestingAuthenticationToken("alice", "", "ROLE_USER")
    auth.isAuthenticated = true
    SecurityContextHolder.getContext().authentication = auth
}

private fun printAdvisorOrders(ctx: ConfigurableApplicationContext, label: String) {
    println("$label 어드바이저 order 재확인")
    println("| bean name | class | order |")
    println("|---|---|---|")
    listOf(
        "org.springframework.transaction.config.internalTransactionAdvisor",
        "postAuthorizeAuthorizationAdvisor",
    ).forEach { name ->
        try {
            val bean = ctx.getBean(name)
            val order = (bean as? Ordered)?.order?.toString() ?: "(not Ordered)"
            println("| $name | ${bean.javaClass.name} | $order |")
        } catch (e: Throwable) {
            println("| $name | (lookup failed: ${e.javaClass.simpleName}: ${e.message}) | - |")
        }
    }
}

/** ownerId 가 authentication.name 과 같은지(pass) 다른지(deny)에 따라 두 케이스를 다 잰다. */
private fun callAndReport(ctx: ConfigurableApplicationContext, tag: String, ownerId: String, jdbc: JdbcTemplate) {
    authenticateAsAlice()
    val probe = ctx.getBean(OrderProbe::class.java)
    println("proxy class = " + probe.javaClass.name)
    try {
        val result = probe.withdraw(tag, ownerId)
        println("withdraw() returned without throwing: $result")
    } catch (e: Throwable) {
        println("withdraw() threw ${e.javaClass.name}: ${e.message}")
    }
    println("sec_log rows where tag='$tag' = ${rowsSec(jdbc, tag)}")
}

fun runS4(ctx: ConfigurableApplicationContext) {
    println("## [S4] 기본 order — @PostAuthorize(500, 바깥) + @Transactional(LOWEST_PRECEDENCE, 안쪽), 거부돼도 행이 남는가")
    println()
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    schemaSec(jdbc)
    printAdvisorOrders(ctx, "[S4]")
    println()
    // 통과 케이스(대조군): ownerId="alice" == authentication.name → 예외 없음, 행 1 기대.
    println("--- (a) 통과 케이스: withdraw(\"s4-allow\", \"alice\")")
    callAndReport(ctx, "s4-allow", "alice", jdbc)
    println()
    // 거부 케이스: ownerId="bob" != authentication.name="alice" → 예외, 그래도 행이 남는지가 관건.
    println("--- (b) 거부 케이스: withdraw(\"s4-deny\", \"bob\")")
    callAndReport(ctx, "s4-deny", "bob", jdbc)
}

fun runS4b() {
    println("## [S4b] TxOuterConfig(@EnableTransactionManagement(order=0, proxyTargetClass=true)) profile=txouter — S4 와 글자 그대로 같은 호출")
    println()
    val ctx2 = SpringApplicationBuilder(SecLabApp::class.java, TxOuterConfig::class.java)
        .properties(
            "spring.main.web-application-type=none",
            "spring.main.banner-mode=off",
            "spring.datasource.url=jdbc:h2:mem:seclab-s4b;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "logging.level.root=WARN",
            "spring.profiles.active=txouter",
        )
        .run()
    try {
        val jdbc = ctx2.getBean(JdbcTemplate::class.java)
        schemaSec(jdbc)
        printAdvisorOrders(ctx2, "[S4b]")
        println()
        callAndReport(ctx2, "s4b-a", "bob", jdbc)
    } finally {
        ctx2.close()
    }
}
