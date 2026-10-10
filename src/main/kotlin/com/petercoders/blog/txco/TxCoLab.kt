package com.petercoders.blog.txco

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.ClassUtils
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource
import kotlin.system.exitProcess

// SPRING-INTERNALS 1편 [e11] — JDBC TransactionManager(Boot 4.1.1 JdbcTransactionManager)에서 suspend fun 에 붙인 @Transactional.
// kotlinx-coroutines 는 이 태스크의 classpath 에만 있다(build.gradle.kts 의 txCo / txCoReactor configuration).
//   ./gradlew --offline -q txCoLab          # kotlinx-coroutines-core 만
//   ./gradlew --offline -q txCoLabReactor   # + kotlinx-coroutines-reactor (Spring 문서가 Coroutine 지원에 요구하는 의존성)
// 조건: Spring MVC 없이 runBlocking 에서 호출, H2 인메모리, Hikari 기본 풀. 각 시점의 Thread·isActualTransactionActive,
// suspension 중 다른 커넥션에서 본 행 수, 예외 뒤 남은 행 수를 찍는다.

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.txco"])
class TxCoApp

data class Point(val where: String, val thread: String, val active: String, val name: String?)

val points = CopyOnWriteArrayList<Point>()

fun mark(where: String) {
    points += Point(where, Thread.currentThread().name, TSM.isActualTransactionActive().toString(),
        TSM.getCurrentTransactionName()?.substringAfterLast('.'))
}

/** Transaction 이 실제로 언제 시작·커밋·롤백됐는지를 같은 목록에 끼워 넣는다. */
@Component
@Profile("txco")
class CoTxListener : TransactionExecutionListener {
    private fun add(what: String, tx: TransactionExecution) {
        points += Point("listener: $what", Thread.currentThread().name, "-", tx.transactionName.substringAfterLast('.'))
    }
    override fun afterBegin(transaction: TransactionExecution, beginFailure: Throwable?) = add("begin", transaction)
    override fun afterCommit(transaction: TransactionExecution, commitFailure: Throwable?) = add("commit", transaction)
    override fun afterRollback(transaction: TransactionExecution, rollbackFailure: Throwable?) = add("rollback", transaction)
}

/** 지금 Thread 에 묶인 커넥션을 쓰지 않고 풀에서 커넥션을 따로 받아 읽는다(= 다른 Transaction 이 보는 값). */
fun visible(ds: DataSource, tag: String): String = ds.connection.use { c ->
    c.prepareStatement("select note from co_log where tag = ? order by id").use { ps ->
        ps.setString(1, tag)
        ps.executeQuery().use { rs ->
            val notes = mutableListOf<String>()
            while (rs.next()) notes += rs.getString(1)
            "${notes.size} $notes"
        }
    }
}

@Service
@Profile("txco")
class CoService(private val jdbc: JdbcTemplate, private val txWorker: TxWorker, private val ds: DataSource) {

    var seenAfterDelay: String = "-"

    /** JDBC TransactionManager + suspend fun + @Transactional. 끝에서 예외를 던진다(롤백 기대). */
    @Transactional
    suspend fun suspendTx(tag: String) {
        mark("1 entry, before first suspension")
        jdbc.update("insert into co_log(tag, note) values (?, 'before-delay')", tag)
        delay(100)
        mark("2 after delay(100)")
        seenAfterDelay = visible(ds, tag)
        withContext(Dispatchers.IO) {
            mark("3 inside withContext(Dispatchers.IO)")
            jdbc.update("insert into co_log(tag, note) values (?, 'in-io')", tag)
        }
        mark("4 after withContext")
        throw IllegalStateException("roll back on purpose")
    }

    /** 대조: suspend fun 에는 @Transactional 을 붙이지 않고, 스레드를 바꾼 뒤 일반 @Transactional 메서드 안에서 경계를 닫는다. */
    suspend fun closedBoundary(tag: String) {
        mark("1 entry (no @Transactional on the suspend fun)")
        delay(100)
        seenAfterDelay = visible(ds, tag)
        withContext(Dispatchers.IO) { txWorker.work(tag) }
    }
}

@Service
@Profile("txco")
class TxWorker(private val jdbc: JdbcTemplate) {
    @Transactional
    fun work(tag: String) {
        mark("2 inside a plain @Transactional fun called within withContext(Dispatchers.IO)")
        jdbc.update("insert into co_log(tag, note) values (?, 'worker')", tag)
        jdbc.update("insert into co_log(tag, note) values (?, 'worker-2')", tag)
        throw IllegalStateException("roll back on purpose")
    }
}

fun main() {
    println("## [e11] @Transactional suspend fun with a JDBC TransactionManager")
    println("kotlinx-coroutines-reactor on classpath = " +
        ClassUtils.isPresent("kotlinx.coroutines.reactor.MonoKt", TxCoApp::class.java.classLoader))
    println("kotlinx-coroutines-core jar = " +
        kotlinx.coroutines.Job::class.java.protectionDomain.codeSource.location.path.substringAfterLast('/'))
    if (ClassUtils.isPresent("kotlinx.coroutines.reactor.MonoKt", TxCoApp::class.java.classLoader))
        println("kotlinx-coroutines-reactor jar = " + Class.forName("kotlinx.coroutines.reactor.MonoKt")
            .protectionDomain.codeSource.location.path.substringAfterLast('/'))
    val ctx = SpringApplicationBuilder(TxCoApp::class.java).properties(
        "spring.main.web-application-type=none",
        "spring.main.banner-mode=off",
        "spring.datasource.url=jdbc:h2:mem:txco;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "logging.level.root=WARN",
        "spring.profiles.active=txco",
    ).run()
    println("transactionManager = ${ctx.getBean(PlatformTransactionManager::class.java).javaClass.name}")
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    val ds = ctx.getBean(DataSource::class.java)
    jdbc.execute("create table co_log(id identity primary key, tag varchar(40), note varchar(40))")
    val svc = ctx.getBean(CoService::class.java)

    val callA: suspend (String) -> Unit = { t -> svc.suspendTx(t) }
    val callB: suspend (String) -> Unit = { t -> svc.closedBoundary(t) }
    for ((label, tag, call) in listOf(
        Triple("(A) @Transactional suspend fun", "co-a", callA),
        Triple("(B) suspend fun → withContext(IO) → plain @Transactional fun", "co-b", callB),
    )) {
        points.clear()
        svc.seenAfterDelay = "-"
        var outcome = "no exception"
        runBlocking {
            try { call(tag) } catch (e: Throwable) { outcome = "caller got ${e.javaClass.name}: ${e.message}" }
        }
        println()
        println("### [e11] $label")
        println("| point | thread | isActualTransactionActive | transaction name |")
        println("|---|---|---|---|")
        points.forEach { println("| ${it.where} | ${it.thread} | ${it.active} | ${it.name ?: "-"} |") }
        println("rows another connection sees right after delay(100) resumed: ${svc.seenAfterDelay}")
        println("outcome: $outcome")
        println("rows left after the exception (another connection): ${visible(ds, tag)}")
    }
    ctx.close()
    exitProcess(0)
}
