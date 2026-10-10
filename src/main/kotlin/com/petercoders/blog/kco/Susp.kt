import kotlinx.coroutines.*
suspend fun step(x: Int): Int { delay(1); return x + 1 }
suspend fun work(): Int {
    val a = step(1)      // suspension point 1 → label 1
    val b = step(a)      // suspension point 2 → label 2
    return a + b
}
fun main() = runBlocking { println(work()) }
