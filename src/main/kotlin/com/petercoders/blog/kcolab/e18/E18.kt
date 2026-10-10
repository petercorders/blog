package com.petercoders.blog.kcolab.e18

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.system.measureTimeMillis

// kco-e18 — IO(64) 위에 HikariCP 풀 10 이면 나머지 IO worker 는 getConnection 에서 기다린다. view 를 풀 크기에 맞추면?
@SpringBootApplication
class E18App

object H2Fns { @JvmStatic fun sleepMs(ms: Int): Int { Thread.sleep(ms.toLong()); return ms } }

// 1 ms 마다 HikariPoolMXBean 을 읽어 피크를 남기는 샘플러
class Sampler(private val ds: HikariDataSource) {
    @Volatile private var running = true
    val maxAwaiting = AtomicInteger(); val maxActive = AtomicInteger()
    private val t = Thread {
        while (running) {
            val p = ds.hikariPoolMXBean
            maxAwaiting.accumulateAndGet(p.threadsAwaitingConnection) { a, b -> maxOf(a, b) }
            maxActive.accumulateAndGet(p.activeConnections) { a, b -> maxOf(a, b) }
            Thread.sleep(1)
        }
    }.apply { isDaemon = true; start() }
    fun stop() { running = false; t.join() }
}

suspend fun fanOut(label: String, ds: HikariDataSource, jdbc: JdbcTemplate, d: CoroutineDispatcher) {
    val threads = ConcurrentHashMap.newKeySet<String>()
    val s = Sampler(ds)
    val ms = measureTimeMillis { coroutineScope { repeat(64) { launch(d) {
        threads += Thread.currentThread().name; jdbc.queryForObject("select SLEEP_MS(200)", Int::class.java)
    } } } }
    s.stop()
    println("%-34s 64 x select SLEEP_MS(200): total=%5d ms  peak threadsAwaitingConnection=%2d  peak activeConnections=%2d  distinct threads=%2d"
        .format(label, ms, s.maxAwaiting.get(), s.maxActive.get(), threads.size))
}

// 요청 하나가 커넥션 c1 을 쥔 채 async(IO) 로 두 번째 커넥션 c2 를 요구한다(Cm = 2)
suspend fun nested(n: Int, ds: HikariDataSource) {
    val ok = AtomicInteger(); val fail = AtomicInteger(); val msgs = ConcurrentHashMap.newKeySet<String>()
    val s = Sampler(ds)
    val ms = measureTimeMillis { coroutineScope { repeat(n) { launch(Dispatchers.IO) {
        val err: Throwable? = runCatching {
            ds.connection.use { c1 ->
                c1.createStatement().use { it.executeQuery("select SLEEP_MS(50)").close() }
                val second = async(Dispatchers.IO) { runCatching { ds.connection.use { c2 -> c2.createStatement().use { it.executeQuery("select SLEEP_MS(50)").close() } } }.exceptionOrNull() }
                second.await()?.let { throw it }
            }
        }.exceptionOrNull()
        if (err == null) ok.incrementAndGet() else { fail.incrementAndGet(); msgs += "${err.javaClass.simpleName}: ${err.message?.take(90)}" }
    } } } }
    s.stop()
    println("nested(c1 held, async c2) concurrent requests=%2d pool=10: ok=%2d failed=%2d total=%5d ms  peak awaiting=%2d  %s"
        .format(n, ok.get(), fail.get(), ms, s.maxAwaiting.get(), msgs.take(1)))
}

fun run() {
    val ctx = SpringApplicationBuilder(E18App::class.java).web(WebApplicationType.NONE)
        .properties("spring.main.banner-mode=off", "logging.level.root=WARN",
            "spring.datasource.url=jdbc:h2:mem:e18;DB_CLOSE_DELAY=-1",
            "spring.datasource.hikari.maximum-pool-size=10", "spring.datasource.hikari.minimum-idle=10",
            "spring.datasource.hikari.connection-timeout=2000", "spring.datasource.hikari.pool-name=e18")
        .run()
    val ds = ctx.getBean(DataSource::class.java) as HikariDataSource
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    jdbc.execute("CREATE ALIAS SLEEP_MS FOR \"com.petercoders.blog.kcolab.e18.H2Fns.sleepMs\"")
    println("HikariDataSource maximumPoolSize=${ds.maximumPoolSize} connectionTimeout=${ds.connectionTimeout} ms")
    runBlocking {
        jdbc.queryForObject("select SLEEP_MS(1)", Int::class.java)   // 워밍업
        repeat(3) { r ->
            println("=== run ${r + 1} ===")
            fanOut("Dispatchers.IO (64)", ds, jdbc, Dispatchers.IO)
            fanOut("IO.limitedParallelism(10, \"db\")", ds, jdbc, Dispatchers.IO.limitedParallelism(10, "db"))
        }
        println("=== Cm = 2 (요청당 커넥션 2개) ===")
        for (n in listOf(5, 9, 10, 12, 20)) nested(n, ds)
    }
    ctx.close()
}
