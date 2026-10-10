import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

// kco-e17 — limitedParallelism 은 suspend 된 채 기다리는 Coroutine 수를 막지 않는다. 막는 것은 kotlinx Semaphore 다.
suspend fun peak(label: String, block: suspend (suspend () -> Unit) -> Unit) {
    val inFlight = AtomicInteger(); val peak = AtomicInteger()
    val body: suspend () -> Unit = { peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }; delay(100); inFlight.decrementAndGet() }
    val ms = measureTimeMillis { block(body) }
    println("%-46s peakInFlight=%3d  total=%5d ms".format(label, peak.get(), ms))
}

fun main() = runBlocking {
    repeat(3) { r ->
        println("=== run ${r + 1} ===")
        val view = Dispatchers.IO.limitedParallelism(4)
        peak("IO.limitedParallelism(4), delay(100) x100") { body -> coroutineScope { repeat(100) { launch(view) { body() } } } }
        val sem = Semaphore(4)
        peak("Semaphore(4).withPermit, delay(100) x100") { body -> coroutineScope { repeat(100) { launch(Dispatchers.IO) { sem.withPermit { body() } } } } }

        // JUC vs kotlinx: permit 가 없을 때 기다리는 두 Coroutine 이 같은 view(병렬도 2)의 세 번째 Coroutine 을 막는가
        val two = Dispatchers.Default.limitedParallelism(2)
        val juc = java.util.concurrent.Semaphore(0)
        var t0 = System.nanoTime()
        val jucStart = coroutineScope {
            repeat(2) { launch(two) { juc.acquire() } }
            delay(20)
            val z = async(two) { (System.nanoTime() - t0) / 1_000_000 }
            launch(Dispatchers.IO) { Thread.sleep(500); juc.release(2) }
            z.await()
        }
        println("JUC Semaphore.acquire() x2 on limitedParallelism(2): third coroutine started at $jucStart ms (permits released at ~500 ms)")
        val ksem = Semaphore(2, acquiredPermits = 2)
        t0 = System.nanoTime()
        val kStart = coroutineScope {
            repeat(2) { launch(two) { ksem.acquire() } }
            delay(20)
            val z = async(two) { (System.nanoTime() - t0) / 1_000_000 }
            launch { delay(500); ksem.release(); ksem.release() }
            z.await()
        }
        println("kotlinx Semaphore.acquire() x2 on limitedParallelism(2): third coroutine started at $kStart ms (permits released at ~500 ms)")
    }
}
