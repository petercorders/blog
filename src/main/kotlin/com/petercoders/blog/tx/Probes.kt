// SPRING-INTERNALS 1편 — @Transactional 프록시 실험용 빈 전체.
// E2(BypassProbe, 아래)는 프록시를 "통과하지 않는" 8가지 호출 경로를 한 클래스에 모아
// 각 경로에서 TransactionSynchronizationManager.isActualTransactionActive() 가 실제로
// true 인지를 잰다: 생성자, @PostConstruct, this.tx() 자기호출, self 필드(=프록시) 경유,
// private/protected/final @Transactional. E1 은 OrderService/PlainService 로 프록시 종류를,
// E3 은 NestedProbe 로 REQUIRES_NEW 자기호출을, E5 는 RollbackProbe 로 롤백 규칙을 본다.
// 실행: cd ~/Desktop/development/blog && ./gradlew --offline -q txLab
// (BypassProbe 의 self 필드 자기주입은 Spring Boot 4 기본값에서 순환참조로 거부되므로
//  TxLab.kt 의 boot() 에 spring.main.allow-circular-references=true 가 필요하다.)
package com.petercoders.blog.tx

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM

/** 실측 한 줄: 어떤 경로로 들어왔을 때 실제 트랜잭션이 열려 있었나. */
data class Hit(val case: String, val expected: Boolean, val active: Boolean, val txName: String?, val note: String = "")

fun hit(case: String, expected: Boolean, note: String = "") =
    Hit(case, expected, TSM.isActualTransactionActive(), TSM.getCurrentTransactionName(), note)

// ---------------------------------------------------------------- E1 프록시 종류

interface OrderPort {
    fun place(): Hit
}

/** 인터페이스를 구현하는 빈. proxyTargetClass 기본값이 무엇이냐에 따라 프록시 종류가 갈린다. */
@Service
class OrderService : OrderPort {
    @Transactional
    override fun place(): Hit = hit("interface bean", true)
}

/** 인터페이스가 없는 빈. CGLIB 말고는 선택지가 없다. */
@Service
class PlainService {
    @Transactional
    fun place(): Hit = hit("no-interface bean", true)
}

// ---------------------------------------------------------------- E2 프록시 미통과 5종

@Service
open class BypassProbe(private val jdbc: JdbcTemplate) {

    @Autowired
    lateinit var self: BypassProbe

    private val postConstructHit = mutableListOf<Hit>()

    init {
        // 생성자 안: 프록시는 아직 존재하지 않는다.
        postConstructHit += hit("constructor(init)", false)
    }

    @PostConstruct
    fun afterInit() {
        // BeanPostProcessor 가 프록시로 감싸기 전에 불린다.
        postConstructHit += tx().copy(case = "@PostConstruct → own @Transactional")
    }

    fun lifecycleHits(): List<Hit> = postConstructHit.toList()

    @Transactional
    fun tx(): Hit = hit("external → proxy", true)

    /** 자기 호출. this 는 타깃이다. */
    fun selfInvoke(): Hit = tx().copy(case = "self-invocation this.tx()")

    /** 필드 주입한 자기 자신(= 프록시)을 거친다. */
    fun viaSelfField(): Hit = self.tx().copy(case = "self field → proxy")

    @Transactional
    private fun privateTx(): Hit = hit("private @Transactional", false)

    fun callPrivate(): Hit = privateTx()

    @Transactional
    protected open fun protectedTx(): Hit = hit("protected @Transactional", false)

    fun callProtected(): Hit = self.protectedTx()

    /** all-open 이 열어줘도 명시적 final 은 그대로 남는다. CGLIB 이 오버라이드하지 못한다. */
    @Transactional
    final fun finalTx(): Hit = hit("final @Transactional", false)

    /** final 메서드가 생성자 주입 필드를 읽는다. 프록시 인스턴스에서 그 필드는 비어 있다. */
    @Transactional
    final fun finalReadsField(): String = "jdbc=" + (jdbc.dataSource?.javaClass?.simpleName ?: "null")

    fun rows(tag: String): Int =
        jdbc.queryForObject("select count(*) from tx_log where tag = ?", Int::class.java, tag) ?: -1
}

// ---------------------------------------------------------------- E3 REQUIRES_NEW 자기 호출

class InnerFailed : RuntimeException("inner failed on purpose")

@Service
class NestedProbe(private val jdbc: JdbcTemplate) {

    @Autowired
    lateinit var self: NestedProbe

    /** 같은 클래스 안에서 REQUIRES_NEW 를 직접 호출한다. */
    @Transactional
    fun outerSelfInvoke(): String {
        jdbc.update("insert into tx_log(tag, note) values ('e3-self','outer')")
        return try {
            requiresNewInner("e3-self"); "no exception"
        } catch (e: InnerFailed) {
            "caught " + e.javaClass.simpleName
        }
    }

    /** 같은 호출을 프록시(self 필드)를 거쳐 한다. */
    @Transactional
    fun outerViaProxy(): String {
        jdbc.update("insert into tx_log(tag, note) values ('e3-proxy','outer')")
        return try {
            self.requiresNewInner("e3-proxy"); "no exception"
        } catch (e: InnerFailed) {
            "caught " + e.javaClass.simpleName
        }
    }

    /** REQUIRED 로 바꾸고 예외를 삼키면 어떻게 되는지. */
    @Transactional
    fun outerRequiredViaProxy(): String {
        jdbc.update("insert into tx_log(tag, note) values ('e3-required','outer')")
        return try {
            self.requiredInner("e3-required"); "no exception"
        } catch (e: InnerFailed) {
            "caught " + e.javaClass.simpleName
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requiresNewInner(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'inner')", tag)
        throw InnerFailed()
    }

    @Transactional(propagation = Propagation.REQUIRED)
    fun requiredInner(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'inner')", tag)
        throw InnerFailed()
    }
}

// ---------------------------------------------------------------- E5 롤백 규칙

/** Kotlin 에는 체크 예외가 없다. 상속 계층은 Java 와 똑같다. */
class DomainFailure(msg: String) : Exception(msg)

@Service
class RollbackProbe(private val jdbc: JdbcTemplate) {

    @Transactional
    fun unchecked(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'r')", tag)
        throw IllegalStateException("unchecked")
    }

    @Transactional
    fun checkedLike(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'r')", tag)
        throw DomainFailure("extends Exception directly")
    }

    @Transactional(rollbackFor = [Exception::class])
    fun checkedLikeWithRule(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'r')", tag)
        throw DomainFailure("extends Exception directly")
    }

    /** rollbackFor 와 noRollbackFor 가 겹칠 때 깊이가 얕은 규칙이 이긴다. */
    @Transactional(rollbackFor = [Exception::class], noRollbackFor = [IllegalStateException::class])
    fun depthRule(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'r')", tag)
        throw IllegalStateException("depth 0 rule wins over depth 3 rule")
    }
}
