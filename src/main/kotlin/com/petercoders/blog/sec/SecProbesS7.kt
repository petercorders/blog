// SPRING-INTERNALS 2편 — [S7] @HandleAuthorizationDenied 대체값이면 예외가 없어 rollback도 없다.
// txouter(Transaction order = 0, 바깥)에서도 거부된 쓰기가 commit되는지를 sec_log 행 수로 본다.
// 실행: ./gradlew --offline -q secLab --args='S7'
package com.petercoders.blog.sec

import org.aopalliance.intercept.MethodInvocation
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.authorization.method.HandleAuthorizationDenied
import org.springframework.security.authorization.method.MethodAuthorizationDeniedHandler
import org.springframework.security.authorization.method.MethodInvocationResult
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 반환 타입(Account)에 맞는 가려진 값을 돌려준다. */
@Component
class AccountMaskingHandler : MethodAuthorizationDeniedHandler {
    override fun handleDeniedInvocation(mi: MethodInvocation, result: AuthorizationResult): Any = Account("masked")
    override fun handleDeniedInvocationResult(mir: MethodInvocationResult, result: AuthorizationResult): Any = Account("masked")
}

@Service
class FallbackWriteProbe(private val jdbc: JdbcTemplate) {
    @Transactional
    @PostAuthorize("returnObject.ownerId == authentication.name")
    @HandleAuthorizationDenied(handlerClass = AccountMaskingHandler::class)
    fun withdrawMasked(tag: String, ownerId: String): Account {
        jdbc.update("insert into sec_log(tag, note) values (?, 'write-before-check')", tag)
        return Account(ownerId)
    }
}

fun runS7() {
    println("## [S7] txouter(order = 0) + @Transactional + @PostAuthorize + @HandleAuthorizationDenied — 거부된 쓰기가 남는가")
    val ctx = bootSec("seclab-s7", "txouter", sources = arrayOf(SecLabApp::class.java, TxOuterConfig::class.java))
    try {
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        ensureSecLog(jdbc)
        val tx = ctx.getBean("org.springframework.transaction.config.internalTransactionAdvisor") as org.springframework.core.Ordered
        println("internalTransactionAdvisor order = ${tx.order}")
        authAs("alice", "ROLE_USER")
        val p = ctx.getBean(FallbackWriteProbe::class.java)
        val r = try { p.withdrawMasked("s7", "bob").toString() } catch (e: Throwable) { "threw ${e.javaClass.name}" }
        println("withdrawMasked(\"s7\", ownerId=\"bob\") as alice -> $r")
        println("sec_log rows where tag='s7' = ${secRows(jdbc, "s7")}")
    } finally {
        ctx.close()
    }
}
