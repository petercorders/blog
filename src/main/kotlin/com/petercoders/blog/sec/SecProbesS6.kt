// [S6] 거부가 실제로 던지는 것 — AuthorizationDeniedException 의 타입 호환성과
// @HandleAuthorizationDenied 의 대체값이 진짜로 반환되는지.
package com.petercoders.blog.sec

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authorization.AuthorizationDeniedException
import org.springframework.security.authorization.method.HandleAuthorizationDenied
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.authorization.method.MethodAuthorizationDeniedHandler
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.authorization.method.MethodInvocationResult
import org.aopalliance.intercept.MethodInvocation
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/** 거부를 예외 대신 대체값으로 바꾸는 핸들러. 반환 타입이 String 이므로 String 을 돌려준다. */
@Component
class MaskingHandler : MethodAuthorizationDeniedHandler {
    override fun handleDeniedInvocation(mi: MethodInvocation, result: AuthorizationResult): Any =
        "가려진 값(핸들러가 돌려줌)"
    override fun handleDeniedInvocationResult(mir: MethodInvocationResult, result: AuthorizationResult): Any =
        "가려진 값(핸들러가 돌려줌)"
}

@Service
open class DenialProbe {
    /** 평범한 거부 — 예외가 밖으로 나간다. */
    @PreAuthorize("hasRole('NOPE')")
    open fun denied(): String = "본문이 실행됐다"

    /** 거부를 예외 대신 대체값으로 받는다. */
    @HandleAuthorizationDenied(handlerClass = MaskingHandler::class)
    @PreAuthorize("hasRole('NOPE')")
    open fun deniedWithFallback(): String = "본문이 실행됐다"
}

fun runS6(ctx: ConfigurableApplicationContext) {
    println("## [S6] 거부가 던지는 것 — 예외 타입과 @HandleAuthorizationDenied 대체값")
    println()
    val p = ctx.getBean(DenialProbe::class.java)
    run {
        val auth = TestingAuthenticationToken("s6-runner", null, "ROLE_USER")
        auth.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = auth
        // (1) 던져지는 예외의 실제 클래스와 상위 타입 호환성
        try {
            p.denied()
            println("denied()               -> 예외 없음 (본문 실행됨)")
        } catch (e: Throwable) {
            println("denied() 예외 클래스    = ${e.javaClass.name}")
            println("  AccessDeniedException 으로 잡히나 = ${e is AccessDeniedException}")
            println("  AuthorizationDeniedException 인가 = ${e is AuthorizationDeniedException}")
            if (e is AuthorizationDeniedException) {
                println("  getAuthorizationResult() = ${e.authorizationResult}")
            }
        }
        // (2) 핸들러가 붙으면 예외 대신 대체값
        val r = try { p.deniedWithFallback() } catch (e: Throwable) { "예외: ${e.javaClass.simpleName}" }
        println("deniedWithFallback()   -> $r")
    }
}
