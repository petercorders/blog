// SPRING-INTERNALS 2편 — 메서드 시큐리티 실험용 빈.
// [S5] SpEL 평가 횟수: @PreAuthorize 는 호출당 몇 번, @PostFilter 는 컬렉션 크기에 따라 몇 번
// 평가되는지를 EvalCounter 로 직접 센다(로그 문자열이 아니라 카운터 값으로 판정).
// 실행: cd ~/Desktop/development/blog && ./gradlew --offline -q secLab --args='S5'
package com.petercoders.blog.sec

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.access.prepost.PostFilter
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** SpEL 안에서 호출되는 카운터 빈. mark() 가 몇 번 불렸는지가 곧 SpEL 평가 횟수다. */
@Component("evalCounter")
class EvalCounter {
    val counts = ConcurrentHashMap<String, AtomicInteger>()
    fun mark(key: String): Boolean {
        counts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        return true
    }
    fun reset() = counts.clear()

    // [S3] 전용: @PreAuthorize SpEL 평가 시점에 트랜잭션이 이미 열려 있는지를 결과로 기록한다.
    @Volatile
    var lastTxActive: Boolean? = null

    fun markTx(tag: String): Boolean {
        lastTxActive = org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()
        println("[markTx] tag=$tag isActualTransactionActive=$lastTxActive")
        return true
    }
}

/**
 * [S5] 전용 프로브.
 * preOnly(): @PreAuthorize 의 SpEL 이 호출당 1회인지.
 * listOfSize(n): @PostFilter 의 SpEL 이 반환 컬렉션의 원소 수만큼(0/3/10) 평가되는지.
 */
@Service
open class FilterProbe {
    @PreAuthorize("@evalCounter.mark('pre')")
    open fun preOnly(): String = "ok"

    @PostFilter("@evalCounter.mark('post')")
    open fun listOfSize(n: Int): MutableList<String> = MutableList(n) { "e$it" }
}

// ---------------------------------------------------------------------------
// [S3]/[S4] @Transactional 과 @PostAuthorize 중 누가 바깥인가.
// withdraw()의 본문은 먼저 sec_log 에 'write-before-check' 행을 쓰고 나서 Account 를 반환한다.
// @PostAuthorize 의 SpEL(returnObject.ownerId == authentication.name)은 반환값을 본 뒤에야
// 평가되므로, insert 는 인가 판정보다 **항상** 먼저 실행된다. 남는 질문은 그 insert 가
// 커밋까지 가느냐(= 어드바이저 order 상 @Transactional 이 안쪽인가)뿐이다.
// [S4]는 기본 order(둘 다 손대지 않은 상태: @PostAuthorize=500 이 바깥, @Transactional=MAX_VALUE
// 가 안쪽)에서 거부돼도 행이 남는지를 잰다.
// ---------------------------------------------------------------------------

/** @PostAuthorize 의 SpEL 이 비교하는 반환값. ownerId 가 authentication.name 과 달라야 거부된다. */
data class Account(val ownerId: String)

@Service
open class OrderProbe(private val jdbc: JdbcTemplate) {
    @Transactional
    @PostAuthorize("returnObject.ownerId == authentication.name")
    open fun withdraw(tag: String, ownerId: String): Account {
        jdbc.update("insert into sec_log(tag, note) values (?, 'write-before-check')", tag)
        return Account(ownerId)
    }
}
