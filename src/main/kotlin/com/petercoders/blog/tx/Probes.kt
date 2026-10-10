// SPRING-INTERNALS 1편 — @Transactional 프록시 실험용 빈 전체(재편 때 더한 빈은 MoreProbes.kt).
// [e2](BypassProbe, 아래)는 Proxy 를 "통과하지 않는" 경로를 한 클래스에 모아
// 각 경로에서 TransactionSynchronizationManager.isActualTransactionActive() 가 실제로
// true 인지를 잰다: 생성자(호출 없음, 기준선), @PostConstruct → this.tx(), this.tx() 자기 호출,
// self 필드(=Proxy) 경유, private/protected/final @Transactional. [e1] 은 OrderService/PlainService 로 Proxy 종류를,
// [e4] 는 final 메서드 NPE 를, [e5] 는 NestedProbe 로 전파를, [e7] 은 RollbackProbe 로 롤백 규칙을 본다.
// 실행: cd ~/Desktop/development/blog && ./gradlew --offline -q txLab
// (BypassProbe 의 self 필드 자기주입은 Spring Boot 4 기본값에서 순환참조로 거부되므로
//  TxLab.kt 의 boot() 에 spring.main.allow-circular-references=true 가 필요하다.
//  self 필드를 가진 빈은 @Profile("!circ-off") 로 묶어, 순환 참조를 끈 [e2d]·[e2g] 컨텍스트에서는 빠지게 한다.)
// 구간 마커는 로그 파일 id 와 같다: [e1]·[e2]·[e4]·[e5]·[e7] … (README 대응표 참조)
package com.petercoders.blog.tx

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM

/** 실측 한 줄: 어떤 경로로 들어왔을 때 실제 트랜잭션이 열려 있었나. */
data class Hit(val case: String, val expected: Boolean, val active: Boolean, val txName: String?, val note: String = "")

fun hit(case: String, expected: Boolean, note: String = "") =
    Hit(case, expected, TSM.isActualTransactionActive(), TSM.getCurrentTransactionName(), note)

// ---------------------------------------------------------------- [e1] 프록시 종류

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

// ---------------------------------------------------------------- [e2] 프록시 미통과 경로 · [e4] final NPE

@Service
@Profile("!circ-off")
open class BypassProbe(private val jdbc: JdbcTemplate) {

    @Autowired
    lateinit var self: BypassProbe

    private val postConstructHit = mutableListOf<Hit>()

    init {
        // 생성자 안: 아무 메서드도 부르지 않는 기준선. 이 시점의 this 는 원본(target)이다.
        postConstructHit += hit("constructor (no call, baseline)", false)
    }

    @PostConstruct
    fun afterInit() {
        // @PostConstruct 는 원본(target) 객체 위에서 돈다. 이 앱은 순환 참조 허용 + self 필드라
        // 이 시점에 early Proxy 가 이미 만들어졌을 수 있다. 그래도 this.tx() 는 자기 호출이라
        // Proxy 를 지나지 않는다. 이 행은 "Proxy 가 아직 없다"의 증거가 아니라 자기 호출의 결과다.
        postConstructHit += tx().copy(case = "@PostConstruct → this.tx()")
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

// ---------------------------------------------------------------- [e5] REQUIRES_NEW 자기 호출 · REQUIRED · NESTED

class InnerFailed : RuntimeException("inner failed on purpose")

@Service
@Profile("!circ-off")
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

    // ------------------------------------------------------------ [e5b] rollback-only 표시 조건 · NESTED

    /** REQUIRED 로 참여한 안쪽이 Exception 상속 예외(DomainFailure)를 던진다. 기본 rollbackOn 은 거짓이다. */
    @Transactional
    fun outerRequiredCheckedViaProxy(tag: String): String {
        jdbc.update("insert into tx_log(tag, note) values (?, 'outer')", tag)
        return try {
            self.requiredInnerChecked(tag); "no exception"
        } catch (e: DomainFailure) {
            "caught " + e.javaClass.simpleName
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    fun requiredInnerChecked(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'inner')", tag)
        throw DomainFailure("participating inner threw an Exception subtype")
    }

    /** NESTED: 같은 커넥션의 savepoint. 안쪽만 되돌리고 바깥은 커밋할 수 있나. */
    @Transactional
    fun outerNestedViaProxy(tag: String): String {
        jdbc.update("insert into tx_log(tag, note) values (?, 'outer')", tag)
        return try {
            self.nestedInner(tag); "no exception"
        } catch (e: InnerFailed) {
            "caught " + e.javaClass.simpleName
        }
    }

    @Transactional(propagation = Propagation.NESTED)
    fun nestedInner(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'inner')", tag)
        throw InnerFailed()
    }

    /** outerRequiredViaProxy 와 같지만 태그를 받는다(플래그를 끈 컨텍스트에서 쓴다). */
    @Transactional
    fun outerRequiredViaProxyTagged(tag: String): String {
        jdbc.update("insert into tx_log(tag, note) values (?, 'outer')", tag)
        return try {
            self.requiredInner(tag); "no exception"
        } catch (e: InnerFailed) {
            "caught " + e.javaClass.simpleName
        }
    }
}

// ---------------------------------------------------------------- [e7] 롤백 규칙

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
        throw IllegalStateException("depth 0 rule wins over depth 2 rule")
    }

    // ------------------------------------------------------------ [e7d] Java 메서드가 던지는 checked 예외

    /** Kotlin 은 선언하지 않는다. Files.readString 이 던지는 NoSuchFileException(IOException 계열)이 그대로 나간다. */
    @Transactional
    fun javaChecked(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'r')", tag)
        java.nio.file.Files.readString(java.nio.file.Path.of("/nonexistent-tx-lab"))
    }

    /** 같은 코드에 @Throws 로 선언만 더한다(바이트코드에 throws IOException 이 붙는다). */
    @Transactional
    @Throws(java.io.IOException::class)
    fun javaCheckedThrows(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'r')", tag)
        java.nio.file.Files.readString(java.nio.file.Path.of("/nonexistent-tx-lab"))
    }

    // ------------------------------------------------------------ [e7e] ALL_EXCEPTIONS 위의 개별 noRollbackFor

    @Transactional(noRollbackFor = [DomainFailure::class])
    fun checkedLikeNoRollback(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'r')", tag)
        throw DomainFailure("extends Exception directly")
    }
}
