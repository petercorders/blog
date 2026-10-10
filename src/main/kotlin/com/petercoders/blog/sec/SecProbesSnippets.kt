// SPRING-INTERNALS 2편 — 본문 요지 코드가 그대로 컴파일되고 도는지 확인하는 마커.
// [S8s] 사용자가 없는 호출은 범위를 좁힌 시스템 Authentication 을 명시적으로 세팅하고 finally 에서 지운다.
// [S16b] 반복되는 식은 meta-annotation 으로 한 곳에 둔다(@PreAuthorize 를 단 커스텀 애노테이션이 그대로 인식되나).
// 실행: ./gradlew --offline -q secLab --args='S8s' / 'S16b'
package com.petercoders.blog.sec

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

// ---- [S8s] 본문 요지 코드(8줄 이하)
fun <T> runAsSystem(block: () -> T): T {
    val ctx = SecurityContextHolder.createEmptyContext()
    ctx.authentication = TestingAuthenticationToken("batch-system", null, "ROLE_ADMIN") // 실제로는 필요한 권한만
    SecurityContextHolder.setContext(ctx)
    try { return block() } finally { SecurityContextHolder.clearContext() }
}

fun runS8s(ctx: ConfigurableApplicationContext) {
    println("## [S8s] 사용자가 없는 스레드(@Scheduled 대역)에서 SecureProbe.secured — 그냥 부르기 vs runAsSystem")
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    ensureSecLog(jdbc)
    val p = ctx.getBean(SecureProbe::class.java)
    val t = Thread({
        val plain = thrownOf { p.secured("s8s-plain") }
        println("no identity -> thrown=$plain rows=${secRows(jdbc, "s8s-plain")}")
        val sys = thrownOf { runAsSystem { p.secured("s8s-system") } }
        println("runAsSystem -> thrown=$sys rows=${secRows(jdbc, "s8s-system")}")
        println("after runAsSystem: SecurityContext authentication=${SecurityContextHolder.getContext().authentication}")
    }, "scheduler-like")
    t.start(); t.join()
}

// ---- [S16b] 본문 요지 코드(5줄 이하)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@PreAuthorize("hasRole('ADMIN')")
annotation class AdminOnly

@Service
class MetaAnnotationProbe(private val jdbc: JdbcTemplate) {
    @AdminOnly
    fun purge(tag: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", tag)
    }
}

fun runS16b(ctx: ConfigurableApplicationContext) {
    println("## [S16b] @PreAuthorize(\"hasRole('ADMIN')\")를 단 meta-annotation @AdminOnly")
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    ensureSecLog(jdbc)
    val p = ctx.getBean(MetaAnnotationProbe::class.java)
    authAs("user", "ROLE_USER")
    println("ROLE_USER  -> thrown=${thrownOf { p.purge("s16b-user") }} rows=${secRows(jdbc, "s16b-user")}")
    authAs("admin", "ROLE_ADMIN")
    println("ROLE_ADMIN -> thrown=${thrownOf { p.purge("s16b-admin") }} rows=${secRows(jdbc, "s16b-admin")}")
}
