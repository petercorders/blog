// SPRING-INTERNALS 2편 — ② @PostAuthorize 거부보다 commit이 먼저 끝난다, 의 추가 마커.
// [S4c] Transaction order=300(Pre 200과 450 사이): Pre는 Transaction 밖, Post는 안인가.
// [S4e] 바깥 @Transactional 서비스가 OrderProbe.withdraw 의 거부 예외를 잡아 삼키면 order마다 무엇이 남나.
// [S4f] 읽기 메서드(@PostAuthorize)에서 인가하고 통과하면 쓰는 패턴 — 같은 bob 호출에서 rows 0인가.
// 실행: ./gradlew --offline -q secLab --args='S4c' / 'S4e' / 'S4f'
package com.petercoders.blog.sec

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional

/** [S4c] Transaction Advisor를 Pre(200)와 authorizeReturnObject(450) 사이에 둔다. */
@Configuration(proxyBeanMethods = false)
@Profile("txmid")
@EnableTransactionManagement(order = 300, proxyTargetClass = true)
class TxMidConfig

/** [S4e] 거부 예외를 잡아 정상 흐름으로 넘기는 바깥 서비스. */
@Service
class OuterService(private val orderProbe: OrderProbe) {
    @Volatile var caught: String? = null

    @Transactional
    fun run(tag: String) {
        try {
            orderProbe.withdraw(tag, "bob")
        } catch (e: AccessDeniedException) {
            caught = e.javaClass.simpleName
        }
    }
}

// [S4f] 본문 요지 코드와 같은 모양. 읽기에서 인가하고 통과해야 쓴다.
@Service
class OwnedReader {
    @PostAuthorize("returnObject.ownerId == authentication.name")
    fun findById(tag: String, ownerId: String): Account = Account(ownerId)
}

@Service
class WriterService(private val reader: OwnedReader, private val jdbc: JdbcTemplate) {
    @Transactional
    fun update(tag: String, ownerId: String) {
        reader.findById(tag, ownerId) // 거부면 여기서 예외가 나서 아래 쓰기는 실행되지 않는다
        jdbc.update("insert into sec_log(tag, note) values (?, 'write-after-read')", tag)
    }
}

private fun orderOf(ctx: ConfigurableApplicationContext, name: String): String =
    ((ctx.getBean(name) as? Ordered)?.order)?.toString() ?: "-"

fun runS4c() {
    println("## [S4c] txmid(@EnableTransactionManagement(order = 300)) — Pre는 밖, Post는 안인가")
    val ctx = bootSec("seclab-s4c", "txmid")
    try {
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        ensureSecLog(jdbc)
        for (n in listOf("preAuthorizeAuthorizationAdvisor", "org.springframework.transaction.config.internalTransactionAdvisor",
                "authorizeReturnObjectAdvisor", "postAuthorizeAuthorizationAdvisor")) {
            println("order $n = ${orderOf(ctx, n)}")
        }
        authAs("alice", "ROLE_ADMIN")
        ctx.getBean(TxOrderProbe::class.java).probe()
        println("(1) markTx(@PreAuthorize SpEL 평가 시점) isActualTransactionActive = ${ctx.getBean(EvalCounter::class.java).lastTxActive}")
        val thrown = thrownOf { ctx.getBean(OrderProbe::class.java).withdraw("s4c", "bob") }
        println("(2) withdraw(\"s4c\", \"bob\") as alice -> thrown=$thrown, sec_log rows=${secRows(jdbc, "s4c")}")
        printChain("(3) OrderProbe", ctx.getBean(OrderProbe::class.java), ctx)
    } finally {
        ctx.close()
    }
}

private fun s4eOnce(label: String, profiles: String, db: String) {
    val ctx = bootSec(db, profiles)
    try {
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        ensureSecLog(jdbc)
        println("[$label] internalTransactionAdvisor order = ${orderOf(ctx, "org.springframework.transaction.config.internalTransactionAdvisor")}")
        authAs("alice", "ROLE_USER")
        val outer = ctx.getBean(OuterService::class.java)
        val tag = "s4e-$label"
        val thrown = thrownOf { outer.run(tag) }
        println("[$label] OuterService.run 안에서 잡은 예외 = ${outer.caught}")
        println("[$label] 바깥으로 나온 예외 = $thrown")
        println("[$label] sec_log rows where tag='$tag' = ${secRows(jdbc, tag)}")
    } finally {
        ctx.close()
    }
}

fun runS4e() {
    println("## [S4e] 바깥 @Transactional 이 withdraw 의 AccessDeniedException 을 catch 로 삼킨다 — 기본 order vs txouter(order = 0)")
    s4eOnce("default", "", "seclab-s4e-a")
    println()
    s4eOnce("txouter", "txouter", "seclab-s4e-b")
}

fun runS4f(ctx: ConfigurableApplicationContext) {
    println("## [S4f] 읽기에서 인가하고 통과하면 쓴다 — WriterService.update -> OwnedReader.findById(@PostAuthorize)")
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    ensureSecLog(jdbc)
    authAs("alice", "ROLE_USER")
    val w = ctx.getBean(WriterService::class.java)
    val deny = thrownOf { w.update("s4f-bob", "bob") }
    println("update(\"s4f-bob\", ownerId=\"bob\") as alice -> thrown=$deny, rows=${secRows(jdbc, "s4f-bob")}")
    val allow = thrownOf { w.update("s4f-alice", "alice") }
    println("update(\"s4f-alice\", ownerId=\"alice\") as alice -> thrown=$allow, rows=${secRows(jdbc, "s4f-alice")}")
}
