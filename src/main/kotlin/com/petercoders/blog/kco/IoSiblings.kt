import kotlinx.coroutines.*
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

// kco-e12 — Thread.sleep(100) 256개를 디스패처 d 위에 던져 걸린 시간·밟은 worker 수·스레드 수를 잰다.
// 케이스마다 resetPeakThreadCount() 를 부르고, 시작·끝의 live 스레드 수(getThreadCount)를 같이 남긴다.
val mx = ManagementFactory.getThreadMXBean()

suspend fun run(name: String, d: CoroutineDispatcher) = coroutineScope {
    val workers = ConcurrentHashMap.newKeySet<String>()
    val peakBeforeReset = mx.peakThreadCount
    mx.resetPeakThreadCount()
    val liveStart = mx.threadCount
    val ms = measureTimeMillis { (1..256).map { launch(d) { workers += Thread.currentThread().name; Thread.sleep(100) } }.joinAll() }
    println("%-28s %5d ms  distinctWorkers=%3d  liveStart=%3d  liveEnd=%3d  peakAfterReset=%3d  (peakBeforeReset=%3d)"
        .format(name, ms, workers.size, liveStart, mx.threadCount, mx.peakThreadCount, peakBeforeReset))
}

fun main(args: Array<String>) = runBlocking {
    val case = args.firstOrNull() ?: "ALL"
    if (case == "ALL" || case == "IO") run("Dispatchers.IO", Dispatchers.IO)
    if (case == "ALL" || case == "LP256") run("IO.limitedParallelism(256)", Dispatchers.IO.limitedParallelism(256))
    if (case == "ALL" || case == "LP16") run("IO.limitedParallelism(16)", Dispatchers.IO.limitedParallelism(16))
    if (case == "ALL") println("Default.limitedParallelism(64) === Default ? ${Dispatchers.Default.limitedParallelism(64) === Dispatchers.Default}")
}
