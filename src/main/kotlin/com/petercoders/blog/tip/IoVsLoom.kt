import kotlinx.coroutines.*
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

// Thread.sleep(100) 1000개를 세 디스패처에 던져 총 시간과 플랫폼 스레드 peak 를 잰다.
// 케이스마다 JVM 을 따로 띄운다 — 앞 케이스가 만든 워커가 60초 keep-alive 동안 살아 있어 peak 를 오염시키기 때문.
suspend fun run(name: String, d: CoroutineDispatcher) {
    val mx = ManagementFactory.getThreadMXBean(); mx.resetPeakThreadCount()
    val ms = measureTimeMillis { coroutineScope { repeat(1000) { launch(d) { Thread.sleep(100) } } } }
    println("%-30s %5d ms  peakPlatformThreads=%d".format(name, ms, mx.peakThreadCount))
}

fun main(args: Array<String>) = runBlocking {
    when (args[0]) {
        "IO"     -> run("Dispatchers.IO", Dispatchers.IO)                                          // 한도 64 → 16바퀴
        "LP1000" -> run("IO.limitedParallelism(1000)", Dispatchers.IO.limitedParallelism(1000))    // 형제, 자기 한도 1000
        "VT"     -> Executors.newVirtualThreadPerTaskExecutor().use { run("VT executor dispatcher", it.asCoroutineDispatcher()) }
    }
}
