// SPRING-INTERNALS 2편 — [S10] 옛 @EnableGlobalMethodSecurity 를 Boot 4.1.1 / Security 7.1.1 에서 켜면.
// sec 패키지를 스캔하지 않는 별도 앱이다(이 패키지만 스캔). @Profile("s10")이라 다른 컨텍스트에는 끼지 않는다.
// (1) secLab 태스크(spring-security-access 없음): 기동 예외 클래스와 메시지 첫 줄.
// (2) secLabExtra 태스크(spring-security-access 있음): Advisor 목록·order, 거부 시 sec_log 행 수.
// 실행: ./gradlew --offline -q secLab --args='S10'   /   ./gradlew -q secLabExtra --args='S10'
@file:Suppress("DEPRECATION")

package com.petercoders.blog.secx.global

import org.springframework.aop.Advisor
import org.springframework.aop.framework.Advised
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.secx.global"])
@EnableGlobalMethodSecurity(prePostEnabled = true)
@Profile("s10")
class GlobalLabApp

data class GAccount(val ownerId: String)

@Service
class GlobalOrderProbe(private val jdbc: JdbcTemplate) {
    @Transactional
    @PostAuthorize("returnObject.ownerId == authentication.name")
    fun withdraw(tag: String, ownerId: String): GAccount {
        jdbc.update("insert into sec_log(tag, note) values (?, 'write-before-check')", tag)
        return GAccount(ownerId)
    }
}

fun runS10() {
    val accessPresent = try { Class.forName("org.springframework.security.access.AccessDecisionManager"); true } catch (e: ClassNotFoundException) { false }
    println("## [S10] @EnableGlobalMethodSecurity(prePostEnabled = true) on Boot 4.1.1 — spring-security-access on classpath = $accessPresent")
    val ctx = try {
        SpringApplicationBuilder(GlobalLabApp::class.java).properties(
            "spring.main.web-application-type=none", "spring.main.banner-mode=off",
            "spring.datasource.url=jdbc:h2:mem:seclab-s10;DB_CLOSE_DELAY=-1",
            "logging.level.root=OFF", "spring.profiles.active=s10",
        ).run()
    } catch (e: Throwable) {
        println("startup failed: ${e.javaClass.name}")
        println("  message first line: ${e.message?.lineSequence()?.firstOrNull()}")
        var c: Throwable? = e.cause
        var depth = 1
        while (c != null && depth <= 6) {
            println("  cause[$depth]: ${c.javaClass.name}: ${c.message?.lineSequence()?.firstOrNull()}")
            c = c.cause; depth++
        }
        return
    }
    try {
        println("startup ok")
        println("| advisor bean | class | order |")
        println("|---|---|---|")
        ctx.getBeanNamesForType(Advisor::class.java).sorted().forEach { n ->
            val b = ctx.getBean(n)
            println("| $n | ${b.javaClass.name} | ${(b as? Ordered)?.order ?: "-"} |")
        }
        val probe = ctx.getBean(GlobalOrderProbe::class.java)
        val advisors = (probe as Advised).advisors
        println("GlobalOrderProbe chain: " + advisors.joinToString(" -> ") { "${it.javaClass.simpleName}(${(it as? Ordered)?.order ?: "-"})" })
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        jdbc.execute("create table if not exists sec_log(id identity primary key, tag varchar(40), note varchar(40))")
        val auth = TestingAuthenticationToken("alice", "", "ROLE_USER"); auth.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = auth
        val thrown = try { probe.withdraw("s10", "bob"); "-" } catch (e: Throwable) { e.javaClass.name }
        val rows = jdbc.queryForObject("select count(*) from sec_log where tag = 's10'", Int::class.java)
        println("withdraw(\"s10\", ownerId=\"bob\") as alice -> thrown=$thrown rows=$rows")
    } finally {
        ctx.close()
    }
}
