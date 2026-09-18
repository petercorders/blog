import kotlinx.coroutines.*
@Volatile var sink = 0L
fun main() = runBlocking<Unit> {
    val spin  = launch(Dispatchers.Default) { var i = 0L; while (true) { sink = i++ } }
    val poll  = launch(Dispatchers.Default) { var i = 0L; while (isActive) { sink = i++ } }
    val dly   = launch(Dispatchers.Default) { try { delay(60_000) } catch (e: CancellationException) { println("delay: ${e.javaClass.name}") } }
    val sleep = launch(Dispatchers.IO) { Thread.sleep(60_000) }
    val rint  = launch(Dispatchers.IO) { try { runInterruptible { Thread.sleep(60_000) } } catch (e: CancellationException) { println("runInterruptible: ${e.javaClass.name} cause=${e.cause?.javaClass?.simpleName}") } }
    delay(200); listOf(spin, poll, dly, sleep, rint).forEach { it.cancel() }; delay(3000)
    println("after 3s: spin isActive=${spin.isActive} isCancelled=${spin.isCancelled} isCompleted=${spin.isCompleted}")
    println("poll=${poll.isCompleted} delay=${dly.isCompleted} sleep(IO)=${sleep.isCompleted} runInterruptible=${rint.isCompleted}")
}
