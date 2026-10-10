package com.petercoders.blog.kcolab.e15

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

// kco-e15 — JDBC(PlatformTransactionManager) 위에서 @Transactional suspend fun 은 어디까지 Transaction 안에서 도나.
object Events {
    private var t0 = System.nanoTime()
    private val q = ConcurrentLinkedQueue<String>()
    fun reset() { t0 = System.nanoTime(); q.clear() }
    fun add(s: String) { q += "  t=%4d ms  %s".format((System.nanoTime() - t0) / 1_000_000, s) }
    fun dump(title: String) { println(title); q.forEach(::println) }
    fun snap(tag: String) = add("$tag: thread=${Thread.currentThread().name} isActualTransactionActive=${TSM.isActualTransactionActive()} txName=${TSM.getCurrentTransactionName()}")
}

// 시작·커밋·롤백 시점을 남기는 DataSourceTransactionManager
class LoggingTxManager(ds: DataSource) : DataSourceTransactionManager(ds) {
    override fun doBegin(transaction: Any, definition: TransactionDefinition) { Events.add("TM begin    on ${Thread.currentThread().name}"); super.doBegin(transaction, definition) }
    override fun doCommit(status: DefaultTransactionStatus) { Events.add("TM COMMIT   on ${Thread.currentThread().name}"); super.doCommit(status) }
    override fun doRollback(status: DefaultTransactionStatus) { Events.add("TM ROLLBACK on ${Thread.currentThread().name}"); super.doRollback(status) }
}

@SpringBootApplication
class E15App {
    @Bean fun transactionManager(ds: DataSource) = LoggingTxManager(ds)
}

@Service
class TxSvc(private val jdbc: JdbcTemplate) {
    @Transactional
    suspend fun probe() {
        Events.snap("body first line (before any suspension)")
        delay(10)
        Events.snap("after delay(10)")
        withContext(Dispatchers.IO) { Events.snap("inside withContext(IO)") }
        Events.snap("body last line")
    }

    @Transactional
    suspend fun insertThenFail() {
        jdbc.update("insert into t(id) values (1)"); Events.snap("inserted 1 (before suspension)")
        delay(10)
        jdbc.update("insert into t(id) values (2)"); Events.snap("inserted 2 (after delay)")
        throw IllegalStateException("boom after two inserts")
    }

    @Transactional
    fun nonSuspendInsertThenFail() {
        Events.snap("non-suspend @Transactional body")
        jdbc.update("insert into t(id) values (11)"); jdbc.update("insert into t(id) values (12)")
        throw IllegalStateException("boom in non-suspend")
    }
}

fun run() {
    val ctx = SpringApplicationBuilder(E15App::class.java).web(WebApplicationType.NONE)
        .properties("spring.main.banner-mode=off", "logging.level.root=WARN", "spring.datasource.url=jdbc:h2:mem:e15;DB_CLOSE_DELAY=-1")
        .run()
    val svc = ctx.getBean(TxSvc::class.java); val jdbc = ctx.getBean(JdbcTemplate::class.java)
    jdbc.execute("create table t(id int primary key)")
    println("TxSvc bean class = ${svc.javaClass.name}  transactionManager = ${ctx.getBean("transactionManager").javaClass.simpleName}")
    runBlocking {
        Events.reset(); Events.add("caller calls probe() on ${Thread.currentThread().name}"); svc.probe(); Events.add("caller resumed after probe()")
        Events.dump("(a)(b)(d) @Transactional suspend fun probe(): delay(10) -> withContext(IO)")

        Events.reset()
        try { svc.insertThenFail() } catch (e: IllegalStateException) { Events.add("caller caught ${e.message}") }
        Events.dump("(c) @Transactional suspend fun: insert 1 -> delay -> insert 2 -> throw")
        println("  rows left in t = ${jdbc.queryForList("select id from t order by id", Int::class.java)}")
        jdbc.update("delete from t")

        Events.reset()
        withContext(Dispatchers.IO) {
            try { svc.nonSuspendInsertThenFail() } catch (e: IllegalStateException) { Events.add("caller caught ${e.message}") }
        }
        Events.dump("(e) control: non-suspend @Transactional called inside withContext(IO): insert 11, 12 -> throw")
        println("  rows left in t = ${jdbc.queryForList("select id from t order by id", Int::class.java)}")
    }
    ctx.close()
}
