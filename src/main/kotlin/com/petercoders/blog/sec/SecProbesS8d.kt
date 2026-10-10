// SPRING-INTERNALS 2편 — [S8d] Coroutine 디스패치 경계에서 SecurityContext 가 따라오나.
// kotlinx-coroutines 는 main 런타임 classpath 에 없다(build.gradle.kts 의 compileOnly + secLabExtra 구성).
// 그래서 이 파일은 secLabExtra 태스크에서만 돈다. 다른 S8 마커와 파일을 나눈 것도 그 때문이다.
// 실행: ./gradlew -q secLabExtra --args='S8d'
package com.petercoders.blog.sec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder

fun runS8d(ctx: ConfigurableApplicationContext) {
    println("## [S8d] runBlocking 안에서 Dispatchers.Default 로 넘긴 뒤 SecureProbe.secured 호출(호출자 alice ROLE_ADMIN)")
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    ensureSecLog(jdbc)
    val p = ctx.getBean(SecureProbe::class.java)
    authAs("alice", "ROLE_ADMIN")
    runBlocking {
        val same = thrownOf { p.secured("s8d-same") }
        println("same thread(${Thread.currentThread().name}) -> thrown=$same rows=${secRows(jdbc, "s8d-same")}")
        withContext(Dispatchers.Default) {
            val who = SecurityContextHolder.getContext().authentication?.name
            val t = thrownOf { p.secured("s8d-default") }
            println("Dispatchers.Default(${Thread.currentThread().name}) authentication=$who -> thrown=$t rows=${secRows(jdbc, "s8d-default")}")
        }
    }
}
