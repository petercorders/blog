package com.petercoders.blog.tip.spring.e4

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.h2.tools.Server
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.StatementCallback
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.ServerSocket
import java.sql.Statement
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

// tip-p14 — 블로킹 JDBC 호출을 무엇이 끊는가. H2 TCP 서버 모드 + HikariCP + JdbcTemplate + DataSourceTransactionManager.
// 오래 걸리는 쿼리 둘:
//   SLEEP : select SLEEP_MS(3000)  — Java alias 안에서 Thread.sleep. H2 는 alias 실행 중 cancel 플래그를 보지 않는다.
//   SCAN  : SYSTEM_RANGE CROSS JOIN 합계 — 행을 돌며 H2 가 cancel·query timeout 을 확인한다(약 3초가 되게 N 을 고른다).
// 케이스(쿼리마다):
//   1 withTimeout(100) { withContext(IO) { jdbc.query } }
//   2 withTimeout(100) { runInterruptible(IO) { jdbc.query } }
//   3a withContext(IO) 안 coroutineContext.job.invokeOnCompletion { stmt.cancel() }   (완료 때만 불린다)
//   3b withTimeout 아래 감시 Coroutine 의 finally 에서 stmt.cancel()               (취소 순간에 불린다)
//   4 JdbcTemplate.queryTimeout = 1  (Statement.setQueryTimeout(1))
//   5 @Transactional(timeout = 1) 메서드 안 JdbcTemplate (DataSourceUtils.applyTransactionTimeout)
//   6 connectionTimeout = 250ms 인 풀 1개를 다른 스레드가 쥐고 있을 때 getConnection
@SpringBootApplication
class E4App

object H2Fns { @JvmStatic fun sleepMs(ms: Int): Int { Thread.sleep(ms.toLong()); return ms } }

@Service
class TxService(private val jdbc: JdbcTemplate) {
    @Transactional(timeout = 1)
    fun longQueryInTx(sql: String): Long? = jdbc.queryForObject(sql, Long::class.java)
}

private fun freePort() = ServerSocket(0).use { it.localPort }

// H2 는 같은 SQL 을 다시 실행하면(테이블 변경이 없으면) 이전 결과를 재사용한다(보정 중 같은 n 두 번째가 0 ms). 그래서 SQL 마다 다른 상수 k 를 붙인다.
private var k = 0
private fun scanSql(n: Int) = "select sum(a.x * b.x) + ${k++} from system_range(1, $n) a, system_range(1, $n) b"
private fun sleepSql() = "select SLEEP_MS(3000) + ${k++} * 0"

private fun startServer(): Pair<Server, String> {
    val port = freePort()
    val server = Server.createTcpServer("-tcp", "-tcpPort", port.toString(), "-ifNotExists").start()
    return server to "jdbc:h2:tcp://localhost:$port/mem:t;DB_CLOSE_DELAY=-1"
}

fun calibrate() {
    val (server, url) = startServer()
    HikariDataSource(HikariConfig().apply { jdbcUrl = url; maximumPoolSize = 1 }).use { ds ->
        val jdbc = JdbcTemplate(ds)
        for (n in (System.getProperty("tip.cal") ?: "500,1000,2000,3000").split(",").map { it.trim().toInt() }) {
            val t0 = System.nanoTime(); jdbc.queryForObject(scanSql(n), Long::class.java)
            println("calibrate SCAN n=$n: ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }
    server.stop()
}

fun run(reps: List<Int>) {
    val n = (System.getProperty("tip.scanN") ?: "6500").toInt()
    val (server, url) = startServer()
    val ctx = SpringApplicationBuilder(E4App::class.java).web(WebApplicationType.NONE)
        .properties("spring.main.banner-mode=off", "logging.level.root=WARN",
            "spring.datasource.url=$url", "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=2",
            "spring.datasource.hikari.pool-name=e4")
        .run()
    val ds = ctx.getBean(DataSource::class.java) as HikariDataSource
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    val tx = ctx.getBean(TxService::class.java)
    jdbc.execute("CREATE ALIAS IF NOT EXISTS SLEEP_MS FOR \"com.petercoders.blog.tip.spring.e4.H2Fns.sleepMs\"")
    println("H2 ${org.h2.engine.Constants.VERSION} TCP server, SCAN n=$n, url=$url | Hikari pool e4 max=${ds.maximumPoolSize} | TxService proxy=${tx.javaClass.name}")
    val queries = linkedMapOf<String, () -> String>("SLEEP" to { sleepSql() }, "SCAN" to { scanSql(n) })
    if (reps.first() == 1) for ((qn, qf) in queries) {
        val q = qf()
        val t0 = System.nanoTime(); jdbc.queryForObject(q, Long::class.java)
        println("baseline $qn without any timeout: ${(System.nanoTime() - t0) / 1_000_000} ms  ($q)")
    }

    fun after(): String {
        val p = ds.hikariPoolMXBean
        val now = "active=${p.activeConnections} idle=${p.idleConnections}"
        val t0 = System.nanoTime()
        while (ds.hikariPoolMXBean.activeConnections > 0 && System.nanoTime() - t0 < 6_000_000_000) Thread.sleep(5)
        val back = (System.nanoTime() - t0) / 1_000_000
        val select1 = runCatching { jdbc.queryForObject("select 1", Int::class.java) }.fold({ "ok($it)" }, { "${it.javaClass.simpleName}: ${it.message?.take(80)}" })
        return "pool right after: $now | all connections idle after +$back ms | then select 1: $select1"
    }
    fun describe(e: Throwable?): String = if (e == null) "returned normally" else buildString {
        append("${e.javaClass.name}: ${e.message?.lineSequence()?.first()?.take(120)}")
        e.cause?.let { append(" | cause=${it.javaClass.name}: ${it.message?.lineSequence()?.first()?.take(100)}") }
        if (e.suppressed.isNotEmpty()) append(" | suppressed=${e.suppressed.joinToString { it.javaClass.name }}")
    }
    // H2 의 Statement.setQueryTimeout 은 세션(커넥션) 설정 QUERY_TIMEOUT 을 바꾸고, Statement 를 닫아도 되돌리지 않는다.
    // Hikari 도 반납 때 되돌리지 않으므로 4·5 뒤 같은 커넥션의 다음 쿼리에 1초 타임아웃이 남는다(run 1 의 첫 시도에서 관측).
    // 그래서 케이스마다 풀의 모든 커넥션에 SET QUERY_TIMEOUT 0 을 건 뒤 잰다(leak 확인 케이스만 예외).
    fun resetSessions() {
        val cs = (1..ds.maximumPoolSize).map { ds.connection }
        cs.forEach { c -> c.createStatement().use { it.execute("SET QUERY_TIMEOUT 0") } }
        cs.forEach { it.close() }
    }
    val note = AtomicReference<String?>(null)
    var t0Case = 0L
    fun sinceStart() = (System.nanoTime() - t0Case) / 1_000_000
    fun case(label: String, reset: Boolean = true, block: () -> Unit) {
        if (reset) resetSessions()
        note.set(null)
        t0Case = System.nanoTime()
        val err = runCatching(block).exceptionOrNull()
        val ms = (System.nanoTime() - t0Case) / 1_000_000
        Thread.sleep(50)   // invokeOnCompletion 처리기가 다른 스레드에서 note 를 남길 시간
        println("%-58s %5d ms  %s".format(label, ms, describe(err)))
        note.get()?.let { println("%-58s        %s".format("", it)) }
        println("%-58s        %s".format("", after()))
    }
    fun cancelAndNote(who: String, ref: AtomicReference<Statement?>) {
        val at = sinceStart()
        val r = runCatching { ref.get()?.cancel() }.exceptionOrNull()
        note.set("$who called Statement.cancel() at +$at ms -> ${r?.let { "${it.javaClass.simpleName}: ${it.message?.lineSequence()?.first()?.take(70)}" } ?: "ok"}")
    }
    fun stmtQuery(ref: AtomicReference<Statement?>, q: String): Long =
        jdbc.execute(StatementCallback { s -> ref.set(s); s.executeQuery(q).use { rs -> rs.next(); rs.getLong(1) } })!!

    for (rep in reps) {
        println("=== run $rep ===")
        for ((qn, qf) in queries) {
            var q = qf()
            case("$qn 1 withTimeout(100){withContext(IO){query}}") { runBlocking { withTimeout(100) { withContext(Dispatchers.IO) { jdbc.queryForObject(q, Long::class.java) } } } }
            q = qf(); case("$qn 2 withTimeout(100){runInterruptible(IO){query}}") { runBlocking { withTimeout(100) { runInterruptible(Dispatchers.IO) { jdbc.queryForObject(q, Long::class.java) } } } }
            q = qf(); case("$qn 3a withTimeout(100) + job.invokeOnCompletion{cancel}") { runBlocking { withTimeout(100) { withContext(Dispatchers.IO) {
                val ref = AtomicReference<Statement?>()
                coroutineContext.job.invokeOnCompletion { cancelAndNote("invokeOnCompletion handler", ref) }
                stmtQuery(ref, q)
            } } } }
            q = qf(); case("$qn 3b withTimeout(100) + watcher finally{cancel}") { runBlocking { withTimeout(100) {
                val ref = AtomicReference<Statement?>()
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { cancelAndNote("watcher finally", ref) } }
                try { withContext(Dispatchers.IO) { stmtQuery(ref, q) } } finally { watcher.cancel() }
            } } }
            q = qf(); case("$qn 4 JdbcTemplate.queryTimeout=1 (setQueryTimeout)") { JdbcTemplate(ds).apply { queryTimeout = 1 }.queryForObject(q, Long::class.java) }
            if (qn == "SCAN") { q = qf(); case("SCAN 4' leak check: plain JdbcTemplate right after 4, no reset", reset = false) { jdbc.queryForObject(q, Long::class.java) } }
            q = qf(); case("$qn 5 @Transactional(timeout=1)") { tx.longQueryInTx(q) }
        }
        // 6: 풀 1개, connectionTimeout 250 ms. 다른 스레드가 SLEEP_MS(1500) 으로 쥐고 있는 동안 getConnection.
        HikariDataSource(HikariConfig().apply { jdbcUrl = url; maximumPoolSize = 1; minimumIdle = 1; connectionTimeout = 250; poolName = "e4-one" }).use { one ->
            val holder = Thread.ofPlatform().start { one.connection.use { c -> c.createStatement().use { it.executeQuery("select SLEEP_MS(1500)").close() } } }
            Thread.sleep(100)
            val t0 = System.nanoTime()
            val err = runCatching { one.connection.use { } }.exceptionOrNull()
            println("%-58s %5d ms  %s".format("6 connectionTimeout=250ms, pool 1 held by another thread", (System.nanoTime() - t0) / 1_000_000, describe(err)))
            holder.join()
        }
    }
    ctx.close(); server.stop()
}
