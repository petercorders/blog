import kotlinx.coroutines.*
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

// Thread.sleep(100) 1000개를 세 디스패처에 던져 총 시간과 플랫폼 스레드 peak 를 잰다(tip-p9).
// 케이스마다 JVM 을 따로 띄운다(앞 케이스가 만든 워커가 다음 케이스의 peak 에 섞이지 않게).
suspend fun run(name: String, d: CoroutineDispatcher) {
    val mx = ManagementFactory.getThreadMXBean(); mx.resetPeakThreadCount()
    val ms = measureTimeMillis { coroutineScope { repeat(1000) { launch(d) { Thread.sleep(100) } } } }
    println("%-30s %5d ms  peakPlatformThreads=%d".format(name, ms, mx.peakThreadCount))
}

// tip-p24 — Thread.sleep(100) 100개, 동시에 sleep 중인 개수의 최대치도 같이 센다.
suspend fun runCapped(name: String, d: CoroutineDispatcher) {
    val mx = ManagementFactory.getThreadMXBean(); mx.resetPeakThreadCount()
    val now = AtomicInteger(); val maxNow = AtomicInteger()
    val virtualSeen = AtomicInteger()
    val ms = measureTimeMillis { coroutineScope { repeat(100) { launch(d) {
        val c = now.incrementAndGet(); maxNow.accumulateAndGet(c) { a, b -> maxOf(a, b) }
        if (Thread.currentThread().isVirtual) virtualSeen.incrementAndGet()
        Thread.sleep(100)
        now.decrementAndGet()
    } } } }
    // 끝난 직후 살아 있는 platform thread 를 이름 앞부분으로 묶는다(carrier = ForkJoinPool-N-worker-M)
    val live = Thread.getAllStackTraces().keys.map { t ->
        when {
            t.name.startsWith("ForkJoinPool-") -> "carrier(ForkJoinPool-*-worker)"
            t.name.startsWith("DefaultDispatcher-worker") -> "DefaultDispatcher-worker"
            else -> "other"
        }
    }.groupingBy { it }.eachCount().toSortedMap()
    println("%-34s 100 x sleep(100): %5d ms  maxConcurrent=%3d  ranOnVirtual=%3d/100  peakPlatformThreads=%d  liveAfter=%s"
        .format(name, ms, maxNow.get(), virtualSeen.get(), mx.peakThreadCount, live))
}

fun main(args: Array<String>) = runBlocking {
    when (args[0]) {
        "IO"     -> run("Dispatchers.IO", Dispatchers.IO)                                          // 한도 64 → 16바퀴
        "LP1000" -> run("IO.limitedParallelism(1000)", Dispatchers.IO.limitedParallelism(1000))    // 형제, 자기 한도 1000
        "VT"     -> Executors.newVirtualThreadPerTaskExecutor().use { run("VT executor dispatcher", it.asCoroutineDispatcher()) }
        // tip-p24
        "VTLP10" -> Executors.newVirtualThreadPerTaskExecutor().use { runCapped("VT.limitedParallelism(10)", it.asCoroutineDispatcher().limitedParallelism(10)) }
        "IOLP10" -> runCapped("IO.limitedParallelism(10)", Dispatchers.IO.limitedParallelism(10))
        "VT100"  -> Executors.newVirtualThreadPerTaskExecutor().use { runCapped("VT executor dispatcher", it.asCoroutineDispatcher()) }
    }
}
