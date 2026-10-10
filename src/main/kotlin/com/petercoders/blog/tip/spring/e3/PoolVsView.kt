package com.petercoders.blog.tip.spring.e3

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

// tip-p13 — 풀 10 앞에 Coroutine 100개. Hikari 지표(threadsAwaitingConnection)는 view 앞 대기열을 보지 못한다.
//   a: Dispatchers.IO(64)                          + 풀 10, 100 x SLEEP_MS(100)
//   b: IO.limitedParallelism(10)                   + 풀 10, 100 x SLEEP_MS(100)
//   e: IO.limitedParallelism(10) 두 개(경로 A·B)    + 풀 10, 50 x 2  (Σn = 20 > 풀)
//   f: IO.limitedParallelism(6) + (4)              + 풀 10, 50 x 2  (Σn = 10 = 풀)
//   c: IO.limitedParallelism(10), 태스크당 커넥션 2개(c1 을 쥔 채 c2) + 풀 10, connectionTimeout 2s, 20개
//   d: c 와 같고 풀 11 (Tn × (Cm − 1) + 1 = 10 × 1 + 1)
// 케이스마다 자식 JVM 을 새로 띄운다(앞 케이스의 IO worker 가 peak 에 섞이지 않게).
object H2Fns { @JvmStatic fun sleepMs(ms: Int): Int { Thread.sleep(ms.toLong()); return ms } }

fun forkAll(rep: String) {
    val java = System.getProperty("java.home") + "/bin/java"
    val cp = System.getProperty("java.class.path")
    println("=== run $rep (케이스마다 새 JVM: $java) ===")
    for (case in listOf("a", "b", "e", "f", "c", "d")) {
        val p = ProcessBuilder(java, "-cp", cp, "com.petercoders.blog.tip.spring.TipLabKt", "E3case", case)
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().forEachLine { println(it) }
        p.waitFor()
    }
}

fun newPool(size: Int, timeoutMs: Long): HikariDataSource {
    val ds = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:mem:e3;DB_CLOSE_DELAY=-1"; maximumPoolSize = size; minimumIdle = size
        connectionTimeout = timeoutMs; poolName = "e3"
    })
    ds.connection.use { c -> c.createStatement().use { it.execute("CREATE ALIAS IF NOT EXISTS SLEEP_MS FOR \"com.petercoders.blog.tip.spring.e3.H2Fns.sleepMs\"") } }
    val t0 = System.currentTimeMillis()
    while (ds.hikariPoolMXBean.idleConnections < size && System.currentTimeMillis() - t0 < 5000) Thread.sleep(10)
    return ds
}

fun query(ds: HikariDataSource, ms: Int) = ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("select SLEEP_MS($ms)").use { it.next() } } }

class Path(val name: String, val d: CoroutineDispatcher, val count: Int) {
    val submitted = AtomicInteger(); val started = AtomicInteger()
}

// 1 ms 마다 Hikari 지표를 읽어 peak 를 남긴다. 50 ms 시점에 한 번 스냅샷을 찍는다.
class Sampler(private val ds: HikariDataSource, private val paths: List<Path>) {
    @Volatile private var running = true
    val maxAwaiting = AtomicInteger(); val maxActive = AtomicInteger()
    @Volatile var snapshot = "(none)"
    private val t0 = System.nanoTime()
    private val t = Thread.ofPlatform().daemon(true).name("sampler").start {
        var snapped = false
        while (running) {
            val p = ds.hikariPoolMXBean
            maxAwaiting.accumulateAndGet(p.threadsAwaitingConnection) { a, b -> maxOf(a, b) }
            maxActive.accumulateAndGet(p.activeConnections) { a, b -> maxOf(a, b) }
            if (!snapped && System.nanoTime() - t0 >= 50_000_000) {
                snapped = true
                val all = Thread.getAllStackTraces()
                val inGetConnection = all.values.count { st -> st.any { it.className == "com.zaxxer.hikari.pool.HikariPool" && it.methodName == "getConnection" } }
                val workers = all.keys.count { it.name.startsWith("DefaultDispatcher-worker") }
                val queue = paths.joinToString(" ") { "${it.name}:${it.submitted.get() - it.started.get()}" }
                snapshot = "at 50ms: awaiting=${p.threadsAwaitingConnection} active=${p.activeConnections} idle=${p.idleConnections} | threads in HikariPool.getConnection=$inGetConnection" +
                    " | queued in front of dispatcher(submitted-started)=[$queue] | live DefaultDispatcher-worker=$workers"
            }
            Thread.sleep(1)
        }
    }
    fun stop() { running = false; t.join() }
}

suspend fun fanOut(label: String, ds: HikariDataSource, paths: List<Path>, queryMs: Int) {
    val mx = ManagementFactory.getThreadMXBean(); mx.resetPeakThreadCount()
    val threads = ConcurrentHashMap.newKeySet<String>()
    val s = Sampler(ds, paths)
    val ms = measureTimeMillis { coroutineScope {
        for (p in paths) repeat(p.count) { p.submitted.incrementAndGet(); launch(p.d) {
            p.started.incrementAndGet(); threads += Thread.currentThread().name; query(ds, queryMs)
        } }
    } }
    s.stop()
    println("[$label] total=$ms ms  peak awaiting=${s.maxAwaiting.get()}  peak active=${s.maxActive.get()}  distinct worker threads=${threads.size}  peakPlatformThreads=${mx.peakThreadCount}")
    println("[$label]   ${s.snapshot}")
}

suspend fun twoConnections(label: String, ds: HikariDataSource, d: CoroutineDispatcher, n: Int) {
    val ok = AtomicInteger(); val fail = AtomicInteger(); val msgs = ConcurrentHashMap.newKeySet<String>()
    val s = Sampler(ds, emptyList())
    val ms = measureTimeMillis { coroutineScope { repeat(n) { launch(d) {
        val err = runCatching {
            ds.connection.use { c1 ->
                c1.createStatement().use { it.executeQuery("select SLEEP_MS(50)").close() }
                ds.connection.use { c2 -> c2.createStatement().use { it.executeQuery("select SLEEP_MS(50)").close() } }   // c1 을 쥔 채 c2
            }
        }.exceptionOrNull()
        if (err == null) ok.incrementAndGet() else { fail.incrementAndGet(); msgs += "${err.javaClass.simpleName}: ${err.message?.take(110)}" }
    } } } }
    s.stop()
    println("[$label] ok=${ok.get()} failed=${fail.get()} total=$ms ms  peak awaiting=${s.maxAwaiting.get()}  peak active=${s.maxActive.get()}  ${msgs.take(1)}")
}

fun runCase(case: String) = runBlocking {
    val io = Dispatchers.IO
    when (case) {
        "a" -> newPool(10, 5000).use { ds -> query(ds, 1); fanOut("a IO(64) + pool 10, 100 tasks", ds, listOf(Path("IO", io, 100)), 100) }
        "b" -> newPool(10, 5000).use { ds -> query(ds, 1); fanOut("b IO.lp(10) + pool 10, 100 tasks", ds, listOf(Path("lp10", io.limitedParallelism(10), 100)), 100) }
        "e" -> newPool(10, 5000).use { ds -> query(ds, 1)
            fanOut("e IO.lp(10) A + IO.lp(10) B + pool 10, 50+50", ds, listOf(Path("A(lp10)", io.limitedParallelism(10), 50), Path("B(lp10)", io.limitedParallelism(10), 50)), 100) }
        "f" -> newPool(10, 5000).use { ds -> query(ds, 1)
            fanOut("f IO.lp(6) A + IO.lp(4) B + pool 10, 50+50", ds, listOf(Path("A(lp6)", io.limitedParallelism(6), 50), Path("B(lp4)", io.limitedParallelism(4), 50)), 100) }
        "c" -> newPool(10, 2000).use { ds -> query(ds, 1); twoConnections("c IO.lp(10), 2 connections/task, pool 10, connectionTimeout 2s, 20 tasks", ds, io.limitedParallelism(10), 20) }
        "d" -> newPool(11, 2000).use { ds -> query(ds, 1); twoConnections("d IO.lp(10), 2 connections/task, pool 11, connectionTimeout 2s, 20 tasks", ds, io.limitedParallelism(10), 20) }
    }
}
