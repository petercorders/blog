import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

// A: Job.cancel() 로 runInterruptible 을 끊는다 / B: Job 은 두고 그 블록을 도는 스레드에 직접 interrupt() 한다.
fun main() = runBlocking {
    val a = launch(Dispatchers.IO) {
        try { runInterruptible { Thread.sleep(60_000) } }
        catch (e: CancellationException) {
            println("A cancel():    ${e.javaClass.simpleName} msg='${e.message}' cause=${e.cause} suppressed=${e.suppressed.size}")
        }
    }
    delay(200); a.cancel(); a.join()

    val target = AtomicReference<Thread>()
    val b = launch(Dispatchers.IO) {
        try { runInterruptible { target.set(Thread.currentThread()); Thread.sleep(60_000) } }
        catch (e: CancellationException) {
            println("B interrupt(): ${e.javaClass.simpleName} msg='${e.message}' cause=${e.cause}")
        }
    }
    delay(200); target.get().interrupt(); b.join()
}
