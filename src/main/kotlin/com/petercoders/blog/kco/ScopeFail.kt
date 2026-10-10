import kotlinx.coroutines.*

val t0 = System.nanoTime()
fun ms() = (System.nanoTime() - t0) / 1_000_000

fun main() = runBlocking {
    try { coroutineScope { val a = async { delay(50); throw IllegalStateException("A failed") }
        val b = async { try { delay(5000); "B" } catch (e: CancellationException) { println("B cancelled at ${ms()} ms"); throw e } }
        println("" + a.await() + b.await()) } }
    catch (e: IllegalStateException) { println("coroutineScope threw ${e.javaClass.simpleName} at ${ms()} ms") }
    supervisorScope { val a = async { delay(50); throw IllegalStateException("A failed") }
        val b = async { delay(300); "B" }
        println("supervisor: b=${b.await()}  a.isCancelled=${a.isCancelled}")
        try { a.await() } catch (e: IllegalStateException) { println("supervisor: a.await() threw '${e.message}'") } }
}
