import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

// kco-e28 — ReentrantLock 을 suspension 너머로 쥐면: 같은 스레드의 다른 Coroutine 은 재진입으로 들어오고,
//           다른 스레드에서 재개된 unlock 은 IllegalMonitorStateException 을 던진다. 대조군은 Mutex.withLock.
fun main() {
    Thread { Thread.sleep(60_000); println("watchdog: 60 s 안에 끝나지 않음 — 교착"); exitProcess(1) }.apply { isDaemon = true }.start()
    repeat(10) { r ->
        val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val lock = ReentrantLock()
        val inside = AtomicInteger(); val maxInside = AtomicInteger(); val imse = AtomicInteger(); val cross = AtomicInteger()
        val samples = ConcurrentLinkedQueue<String>()
        val ms = measureTimeMillis {
            runBlocking(pool) {
                repeat(20) { i -> launch {
                    val lockT = Thread.currentThread().name
                    lock.lock()
                    maxInside.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                    try { delay(10) } finally {
                        inside.decrementAndGet()
                        val unlockT = Thread.currentThread().name
                        if (unlockT != lockT) cross.incrementAndGet()
                        try { lock.unlock() } catch (e: IllegalMonitorStateException) {
                            imse.incrementAndGet(); samples += "c$i lock=$lockT unlock=$unlockT -> IllegalMonitorStateException"
                        }
                    }
                } }
            }
        }
        println("ReentrantLock run ${r + 1}: IMSE=${imse.get()}/20  unlockOnOtherThread=${cross.get()}  maxInsideCriticalSection=${maxInside.get()}  stillLocked=${lock.isLocked}  total=$ms ms  ${samples.joinToString()}")
        pool.close()
    }
    repeat(3) { r ->
        val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val mutex = Mutex(); val inside = AtomicInteger(); val maxInside = AtomicInteger(); val cross = AtomicInteger()
        val ms = measureTimeMillis {
            runBlocking(pool) {
                repeat(20) { launch {
                    val lockT = Thread.currentThread().name
                    mutex.withLock {
                        maxInside.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                        delay(10); inside.decrementAndGet()
                    }
                    if (Thread.currentThread().name != lockT) cross.incrementAndGet()
                } }
            }
        }
        println("Mutex.withLock run ${r + 1}: errors=0  resumedOnOtherThread=${cross.get()}  maxInsideCriticalSection=${maxInside.get()}  total=$ms ms")
        pool.close()
    }
    exitProcess(0)
}
