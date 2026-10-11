import kotlinx.coroutines.*
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

// kco-e31 — kco-e12 의 live 스레드 수에는 worker 가 아닌 스레드도 섞인다(별도 JVM LP16: live 6 -> 25~27, worker 16).
// 그래서 케이스마다 JVM 을 따로 띄우고, Thread.sleep(100) 256개를 던지는 동안
//   - 이름이 DefaultDispatcher-worker- 로 시작하는 live 스레드 수를 1ms 간격으로 센 최댓값(peakWorkers)
//   - 끝난 뒤 살아 있는 worker 수(aliveWorkers, keep-alive 60초라 실행 중 만들어진 worker 는 다 남아 있다)
//   - 실제로 태스크를 실행한 worker 수(distinctWorkers)
//   - 시작 뒤 새로 생긴 worker 아닌 스레드의 이름
// 을 찍는다. 스레드 목록은 루트 ThreadGroup.enumerate 로 얻는다(스택을 걷지 않는다).
val mx = ManagementFactory.getThreadMXBean()
const val PREFIX = "DefaultDispatcher-worker-"

fun liveThreads(): List<Thread> {
    var g = Thread.currentThread().threadGroup
    while (g.parent != null) g = g.parent
    val arr = arrayOfNulls<Thread>(g.activeCount() * 2 + 64)
    val n = g.enumerate(arr, true)
    return arr.take(n).filterNotNull()
}

fun workerCount() = liveThreads().count { it.name.startsWith(PREFIX) }

suspend fun run(name: String, d: CoroutineDispatcher) = coroutineScope {
    val ran = ConcurrentHashMap.newKeySet<String>()
    val before = liveThreads().map { it.name }.toSet()
    val peakWorkers = AtomicInteger(workerCount())
    val sampling = AtomicBoolean(true)
    val sampler = Thread({
        while (sampling.get()) { peakWorkers.accumulateAndGet(workerCount(), ::maxOf); Thread.sleep(1) }
    }, "worker-sampler").apply { isDaemon = true }
    sampler.start()
    mx.resetPeakThreadCount()
    val liveStart = mx.threadCount
    val ms = measureTimeMillis { (1..256).map { launch(d) { ran += Thread.currentThread().name; Thread.sleep(100) } }.joinAll() }
    sampling.set(false)
    sampler.join()
    val after = liveThreads()
    val alive = after.filter { it.name.startsWith(PREFIX) }
    val maxIndex = alive.maxOfOrNull { it.name.removePrefix(PREFIX).substringBefore(' ').toIntOrNull() ?: 0 } ?: 0
    val newOthers = after.map { it.name }.filter { !it.startsWith(PREFIX) && it !in before && it != "worker-sampler" }.sorted()
    println("%-28s %5d ms  distinctWorkers=%3d  aliveWorkers=%3d  peakWorkers=%3d  maxWorkerIndex=%3d  liveStart=%3d  liveEnd=%3d  peakAfterReset=%3d"
        .format(name, ms, ran.size, alive.size, peakWorkers.get(), maxIndex, liveStart, mx.threadCount, mx.peakThreadCount))
    println("    new non-worker threads since start (%d): %s".format(newOthers.size, newOthers))
}

fun main(args: Array<String>) = runBlocking {
    when (args.firstOrNull() ?: "IO") {
        "IO" -> run("Dispatchers.IO", Dispatchers.IO)
        "LP256" -> run("IO.limitedParallelism(256)", Dispatchers.IO.limitedParallelism(256))
        "LP16" -> run("IO.limitedParallelism(16)", Dispatchers.IO.limitedParallelism(16))
    }
}
