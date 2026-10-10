// SPRING-INTERNALS 2편(Method Security) — 새 마커(S1b~S16)가 함께 쓰는 도우미.
// 프로필마다 컨텍스트를 따로 띄워(H2 DB 이름도 따로) 마커끼리 설정이 섞이지 않게 한다.
// 판정은 로그 문자열이 아니라 예외 클래스와 sec_log 행 수로만 한다.
package com.petercoders.blog.sec

import org.springframework.aop.framework.Advised
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.Ordered
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/** 실행 JVM을 로그에 직접 남긴다(Gradle 툴체인이 고른 JVM인지 셸 기본값인지 헷갈리지 않게). */
fun printRuntimeHeader() {
    println("# runtime: java.runtime.version=${System.getProperty("java.runtime.version")}" +
        " java.vendor=${System.getProperty("java.vendor")}" +
        " java.vm.vendor=${System.getProperty("java.vm.vendor")}")
}

/** SecLabApp(sec 패키지 스캔)을 프로필·DB 이름·추가 프로퍼티를 바꿔 새로 띄운다. */
fun bootSec(db: String, profiles: String = "", vararg extra: String, sources: Array<Class<*>> = arrayOf(SecLabApp::class.java)): ConfigurableApplicationContext {
    val props = mutableListOf(
        "spring.main.web-application-type=none",
        "spring.main.banner-mode=off",
        "spring.datasource.url=jdbc:h2:mem:$db;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "logging.level.root=WARN",
        "spring.main.allow-circular-references=true",
    )
    if (profiles.isNotEmpty()) props += "spring.profiles.active=$profiles"
    props += extra
    return SpringApplicationBuilder(*sources).properties(*props.toTypedArray()).run()
}

fun ensureSecLog(jdbc: JdbcTemplate) {
    jdbc.execute("create table if not exists sec_log(id identity primary key, tag varchar(40), note varchar(40))")
}

fun secRows(jdbc: JdbcTemplate, tag: String): Int =
    jdbc.queryForObject("select count(*) from sec_log where tag = ?", Int::class.java, tag) ?: -1

fun authAs(name: String, vararg authorities: String) {
    val auth = TestingAuthenticationToken(name, "", *authorities)
    auth.isAuthenticated = true
    val ctx = SecurityContextHolder.createEmptyContext()
    ctx.authentication = auth
    SecurityContextHolder.setContext(ctx)
}

/** 예외를 삼키지 않고 "클래스 이름"으로 바꾼다. 통과면 "-". */
inline fun thrownOf(block: () -> Unit): String = try {
    block(); "-"
} catch (e: Throwable) {
    e.javaClass.name
}

/** 내 Bean의 실제 Advisor 체인(Proxy가 든 배열 순서 그대로)을 찍는다. 진단 레시피와 같은 코드다. */
fun printChain(label: String, bean: Any, ctx: ConfigurableApplicationContext? = null) {
    val advisors = (bean as Advised).advisors
    // 컨테이너의 Advisor Bean 과 같은 인스턴스면 Bean 이름을 붙인다(체인 원소가 어느 애노테이션 것인지 보이게).
    val names = ctx?.getBeanNamesForType(org.springframework.aop.Advisor::class.java)
        ?.associateBy { ctx.getBean(it) as Any } ?: emptyMap()
    println("$label chain (${advisors.size}):")
    advisors.forEachIndexed { i, a ->
        val order = (a as? Ordered)?.order?.toString() ?: "-"
        val advice = a.advice.javaClass.simpleName
        val name = names.entries.firstOrNull { it.key === a }?.value?.let { " bean=$it" } ?: ""
        println("  [$i] ${a.javaClass.simpleName}$name order=$order advice=$advice")
    }
}
