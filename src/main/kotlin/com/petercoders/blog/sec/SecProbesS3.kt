// SPRING-INTERNALS 3편(절 3) — [S3] 시큐리티 어드바이저 vs 트랜잭션 어드바이저, 어느 쪽이 바깥인가.
// 1) Ordered.getOrder() 를 어드바이저 빈에서 직접 읽는다(문서 숫자를 옮겨 적지 않는다).
// 2) 진입 순서를 "결과"로 판정한다: @PreAuthorize 의 SpEL 안에서 EvalCounter.markTx 를 불러
//    그 시점의 TransactionSynchronizationManager.isActualTransactionActive() 를 기록한다.
//    markTx 가 찍은 값이 false 면 시큐리티가 바깥(트랜잭션 시작 전에 인가 평가), true 면 그 반대다.
// 3) Ordered.LOWEST_PRECEDENCE 상수값도 같이 찍어 트랜잭션 어드바이저의 기본 order 와 비교한다.
// 실행: cd ~/Desktop/development/blog && ./gradlew --offline -q secLab --args='S3'
package com.petercoders.blog.sec

import org.springframework.aop.Advisor
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.Ordered
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** [S3] 전용 프로브 — 같은 메서드에 @PreAuthorize 와 @Transactional 을 함께 얹는다. */
@Service
open class TxOrderProbe {
    @PreAuthorize("@evalCounter.markTx('preauthorize')")
    @Transactional
    open fun probe(): String = "probe body executed"
}

fun runS3(ctx: ConfigurableApplicationContext) {
    println("## [S3] 시큐리티 어드바이저 vs 트랜잭션 어드바이저 — order 값과 진입 순서")

    println()
    println("### 1) Ordered.getOrder() 를 어드바이저 빈에서 직접 읽는다")
    println("| bean name | class | order |")
    println("|---|---|---|")
    ctx.getBeanNamesForType(Advisor::class.java).sorted().forEach { name ->
        val bean = ctx.getBean(name)
        val order = (bean as? Ordered)?.order?.toString() ?: "(not Ordered)"
        println("| $name | ${bean.javaClass.name} | $order |")
    }

    println()
    println("### 3) Ordered.LOWEST_PRECEDENCE = ${Ordered.LOWEST_PRECEDENCE}")

    println()
    println("### 2) 진입 순서를 결과로 판정 — @PreAuthorize SpEL 평가 시점의 isActualTransactionActive()")
    val counter = ctx.getBean(EvalCounter::class.java)
    val probe = ctx.getBean(TxOrderProbe::class.java)
    val result = probe.probe()
    println("probe() -> $result")
    println("markTx 평가 시점 isActualTransactionActive() = ${counter.lastTxActive}")
}
