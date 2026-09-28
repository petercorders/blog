// SPRING-INTERNALS 2편(메서드 시큐리티도 프록시 위에 산다) — 실험 러너.
// 절 번호는 기획 문서(planning/2-method-security-proxy-outline.md)의 "절마다 넣을 실험" 표를 따른다.
// 이 파일은 현재 [S5] 절만 구현한다: SpEL 이 호출당 몇 번 평가되는지를
// @Component("evalCounter") 카운터로 직접 센다 — 로그 문자열이 아니라 카운터 값으로 판정한다.
//
// 실행: cd ~/Desktop/development/blog && ./gradlew --offline -q secLab --args='S5'
//
// 웹 스타터는 넣지 않는다(spring.main.web-application-type=none). @PreAuthorize/@PostFilter
// 의 SpEL 평가 자체는 인가 통과 여부와 무관하지만, 메서드 인터셉터가 SpEL을 평가하려면
// SecurityContextHolder 에 Authentication 이 있어야 하므로 TestingAuthenticationToken 을
// 직접 채워 넣는다(1편의 allow-circular-references 처럼, 여기서는 인증 공급만 대신한다).
package com.petercoders.blog.sec

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.system.exitProcess

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.sec"])
@EnableMethodSecurity
class SecLabApp

private fun boot(): ConfigurableApplicationContext =
    SpringApplicationBuilder(SecLabApp::class.java)
        .properties(
            "spring.main.web-application-type=none",
            "spring.main.banner-mode=off",
            // [S4] OrderProbe 가 JdbcTemplate 을 쓴다. [S5]는 DB 를 안 쓰므로 무해하다.
            "spring.datasource.url=jdbc:h2:mem:seclab;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "logging.level.root=WARN",
            // [S2] SecureProbe 의 self 필드 자기주입 — 1편(BypassProbe)과 같은 이유로 순환참조 허용이 필요.
            "spring.main.allow-circular-references=true",
        )
        .run()

/** SpEL 평가가 정상적으로 일어나려면 컨텍스트에 인증된 Authentication 이 있어야 한다. */
private fun authenticate() {
    val auth = TestingAuthenticationToken("s5-runner", null, "ROLE_ADMIN")
    auth.isAuthenticated = true
    SecurityContextHolder.getContext().authentication = auth
}

private fun row(annotation: String, inputSize: String, evals: Any, returnedSize: String) =
    "| %-13s | %-10s | %-10s | %-14s |".format(annotation, inputSize, evals.toString(), returnedSize)

/**
 * [S1] 메서드 시큐리티 어드바이저가 실제로 어떻게 등록되는가.
 * - internalAutoProxyCreator 의 실제 구현 클래스가 1편(@Transactional)과 같은지.
 * - Advisor 빈 전체 목록과 order(트랜잭션 어드바이저의 Ordered.MAX_VALUE 포함).
 * - preAuthorizeAuthorizationMethodInterceptor 빈의 실제 클래스.
 */
private fun runS1(ctx: ConfigurableApplicationContext) {
    println("## [S1] 메서드 시큐리티 어드바이저 등록")
    println(
        "spring-security-core version = " +
            org.springframework.security.core.SpringSecurityCoreVersion.getVersion())
    println("spring-core version           = " + org.springframework.core.SpringVersion.getVersion())

    val autoProxyCreator = ctx.getBean("org.springframework.aop.config.internalAutoProxyCreator")
    println("internalAutoProxyCreator = " + autoProxyCreator.javaClass.name)

    println()
    println("| advisor bean name | actual class | order |")
    println("|---|---|---|")
    ctx.getBeanNamesForType(org.springframework.aop.Advisor::class.java).sorted().forEach { name ->
        val bean = ctx.getBean(name)
        val order = (bean as? org.springframework.core.Ordered)?.order?.toString() ?: "-"
        println("| $name | ${bean.javaClass.name} | $order |")
    }

    println()
    val interceptor = ctx.getBean("preAuthorizeAuthorizationMethodInterceptor")
    println("preAuthorizeAuthorizationMethodInterceptor class = " + interceptor.javaClass.name)
}

// [S4] 은 이제 SecProbesS4.kt 의 runS4(ctx)/runS4b() 로 옮겨졌다(여기 있던 중복 정의를
// 지웠다 — 두 파일에 같은 시그니처가 있으면 컴파일이 "Conflicting overloads" 로 죽는다).

/**
 * [S5] SpEL 은 언제, 몇 번 평가되는가.
 * - preOnly(): @PreAuthorize 는 호출 1건당 1회 평가되는지.
 * - listOfSize(n): @PostFilter 는 반환 컬렉션의 원소마다 한 번씩(= n번) 평가되는지, n = 0/3/10.
 */
private fun runS5(ctx: ConfigurableApplicationContext) {
    val counter = ctx.getBean(EvalCounter::class.java)
    val probe = ctx.getBean(FilterProbe::class.java)

    println("## [S5] SpEL 평가 횟수 — @PreAuthorize 1회 vs @PostFilter 원소 수")
    println("| annotation    | input size | spel evals | returned size  |")
    println("|---------------|------------|------------|----------------|")

    counter.reset()
    try {
        probe.preOnly()
        val evals = counter.counts["pre"]?.get() ?: 0
        println(row("@PreAuthorize", "-", evals, "-"))
    } catch (e: Throwable) {
        println(row("@PreAuthorize", "-", "EXC:${e.javaClass.simpleName}", e.message ?: "-"))
    }

    for (n in listOf(0, 3, 10)) {
        counter.reset()
        try {
            val result = probe.listOfSize(n)
            val evals = counter.counts["post"]?.get() ?: 0
            println(row("@PostFilter", n.toString(), evals, result.size.toString()))
        } catch (e: Throwable) {
            println(row("@PostFilter", n.toString(), "EXC:${e.javaClass.simpleName}", e.message ?: "-"))
        }
    }
}

fun main(args: Array<String>) {
    val section = args.getOrNull(0) ?: "S5"
    val ctx = boot()
    authenticate()
    try {
        when (section) {
            "S1" -> runS1(ctx)
            "S2" -> runS2(ctx)
            "S3" -> runS3(ctx)
            "S4" -> runS4(ctx)
            "S4b" -> runS4b()
            "S5" -> runS5(ctx)
            "S6" -> runS6(ctx)
            else -> {
                System.err.println("unknown section: $section (only S1, S2, S3, S4, S4b, S5, S6 are implemented in this file)")
                exitProcess(1)
            }
        }
    } finally {
        ctx.close()
    }
}
