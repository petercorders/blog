import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis
fun main() = runBlocking {
    val one = Dispatchers.Default.limitedParallelism(1)
    val sleepMs = measureTimeMillis { (1..100).map { launch(one) { Thread.sleep(100) } }.joinAll() }
    val delayMs = measureTimeMillis { (1..100).map { launch(one) { delay(100) } }.joinAll() }
    println("limitedParallelism(1): Thread.sleep x100 = $sleepMs ms, delay x100 = $delayMs ms")
}
