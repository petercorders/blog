// SPRING-INTERNALS 2편 — [S4d] @EnableMethodSecurity(offset = N) 이 미치는 범위와 결과.
// sec 패키지(SecLabApp, offset 0)와 섞이지 않도록 별도 패키지·별도 앱(k1000, kmax)이 이 패키지만 스캔한다.
// 실행: ./gradlew --offline -q secLab --args='S4d'
package com.petercoders.blog.secx.offset.common

import org.springframework.aop.Advisor
import org.springframework.aop.framework.Advised
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.Ordered
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class OAccount(val ownerId: String)

@Service
class OffsetOrderProbe(private val jdbc: JdbcTemplate) {
    @Transactional
    @PostAuthorize("returnObject.ownerId == authentication.name")
    fun withdraw(tag: String, ownerId: String): OAccount {
        jdbc.update("insert into sec_log(tag, note) values (?, 'write-before-check')", tag)
        return OAccount(ownerId)
    }
}

fun runOffset(label: String, app: Class<*>) {
    val ctx = SpringApplicationBuilder(app).properties(
        "spring.main.web-application-type=none", "spring.main.banner-mode=off",
        "spring.datasource.url=jdbc:h2:mem:seclab-s4d-$label;DB_CLOSE_DELAY=-1",
        "logging.level.root=WARN", "spring.profiles.active=s4d",
    ).run()
    try {
        println("[$label] advisor orders:")
        ctx.getBeanNamesForType(Advisor::class.java).filter { it.endsWith("Advisor") }.sorted().forEach { n ->
            println("  $n = ${(ctx.getBean(n) as? Ordered)?.order ?: "-"}")
        }
        val probe = ctx.getBean(OffsetOrderProbe::class.java)
        println("[$label] OffsetOrderProbe chain: " + (probe as Advised).advisors.joinToString(" -> ") {
            "${it.javaClass.simpleName}(${(it as? Ordered)?.order ?: "-"})"
        })
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        jdbc.execute("create table if not exists sec_log(id identity primary key, tag varchar(40), note varchar(40))")
        val auth = TestingAuthenticationToken("alice", "", "ROLE_USER"); auth.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = auth
        val tag = "s4d-$label"
        val thrown = try { probe.withdraw(tag, "bob"); "-" } catch (e: Throwable) { e.javaClass.name }
        val rows = jdbc.queryForObject("select count(*) from sec_log where tag = ?", Int::class.java, tag)
        println("[$label] withdraw(\"$tag\", ownerId=\"bob\") as alice -> thrown=$thrown rows=$rows")
    } finally {
        ctx.close()
    }
}
