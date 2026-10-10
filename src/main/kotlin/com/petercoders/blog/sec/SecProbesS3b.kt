// SPRING-INTERNALS 2편 — [S3b] Transaction을 바깥(order=0)으로 빼면 @PreAuthorize가 Transaction 안에서 평가되는가.
//                       [S3c] @PreAuthorize("@ownerCheck.check(#id)")의 check Bean에 @Transactional(readOnly = true)를
//                             걸면 check가 자기 Transaction 안에서 읽는가(기본 order, 프로브 본문에는 @Transactional 없음).
// 실행: ./gradlew --offline -q secLab --args='S3b'   /   --args='S3c'
package com.petercoders.blog.sec

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM

/** [S3c] SpEL에서 @ownerCheck로 부르는 check Bean. Bean 참조라 Proxy를 지난다. */
@Component("ownerCheck")
class OwnerCheck {
    @Volatile var active: Boolean? = null
    @Volatile var readOnly: Boolean? = null

    @Transactional(readOnly = true)
    fun check(id: String): Boolean {
        active = TSM.isActualTransactionActive()
        readOnly = TSM.isCurrentTransactionReadOnly()
        return true
    }
}

/** [S3c] 대조군: @Transactional 없는 check. #13152가 깨진 모양(검사 안 조회가 Transaction 밖)이다. */
@Component("ownerCheckPlain")
class OwnerCheckPlain {
    @Volatile var active: Boolean? = null

    fun check(id: String): Boolean {
        active = TSM.isActualTransactionActive()
        return true
    }
}

@Service
class OwnerCheckProbe {
    @PreAuthorize("@ownerCheck.check(#id)")
    fun byId(id: String): String = "body active=${TSM.isActualTransactionActive()}"

    @PreAuthorize("@ownerCheckPlain.check(#id)")
    fun byIdPlain(id: String): String = "body active=${TSM.isActualTransactionActive()}"
}

fun runS3b() {
    println("## [S3b] txouter(@EnableTransactionManagement(order = 0)) — @PreAuthorize SpEL 평가 시점의 isActualTransactionActive()")
    val ctx = bootSec("seclab-s3b", "txouter", sources = arrayOf(SecLabApp::class.java, TxOuterConfig::class.java))
    try {
        val tx = ctx.getBean("org.springframework.transaction.config.internalTransactionAdvisor") as org.springframework.core.Ordered
        println("internalTransactionAdvisor order = ${tx.order}")
        val counter = ctx.getBean(EvalCounter::class.java)
        authAs("alice", "ROLE_ADMIN")
        val r = ctx.getBean(TxOrderProbe::class.java).probe()
        println("probe() -> $r")
        println("markTx 평가 시점 isActualTransactionActive() = ${counter.lastTxActive}")
    } finally {
        ctx.close()
    }
}

fun runS3c(ctx: ConfigurableApplicationContext) {
    println("## [S3c] 기본 order — @PreAuthorize(\"@ownerCheck.check(#id)\") 안의 Transaction 상태")
    authAs("alice", "ROLE_USER")
    val probe = ctx.getBean(OwnerCheckProbe::class.java)
    val check = ctx.getBean(OwnerCheck::class.java)
    val plain = ctx.getBean(OwnerCheckPlain::class.java)
    val r1 = probe.byId("alice")
    println("ownerCheck(@Transactional(readOnly = true)) 안: isActualTransactionActive=${check.active} isCurrentTransactionReadOnly=${check.readOnly}")
    println("byId 본문(@Transactional 없음): $r1")
    val r2 = probe.byIdPlain("alice")
    println("ownerCheckPlain(@Transactional 없음) 안: isActualTransactionActive=${plain.active}")
    println("byIdPlain 본문: $r2")
}
