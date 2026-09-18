import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

val tl = ThreadLocal<String>()

suspend fun probe(tag: String, expected: String, set: Boolean): String? {
    if (set) tl.set(expected)
    val before = Thread.currentThread().name
    delay(10)                                   // 디스패치 경계
    val got = tl.get()
    return if (got == expected) null else "$tag before=$before after=${Thread.currentThread().name} tl=$got"
}

fun main() = runBlocking {
    val n = 64
    val mismatchesA = ConcurrentLinkedQueue<String>()
    val mismatchesB = ConcurrentLinkedQueue<String>()
    val jobsA = (1..n).map { async(Dispatchers.Default) {
        val r = probe("plain$it", "plain$it-value", set = true)
        if (r != null) mismatchesA += r
    } }
    jobsA.awaitAll()
    val jobsB = (1..n).map { async(Dispatchers.Default + tl.asContextElement("ctx$it-value")) {
        val r = probe("ctx$it", "ctx$it-value", set = false)
        if (r != null) mismatchesB += r
    } }
    jobsB.awaitAll()
    println("plain ThreadLocal mismatches=${mismatchesA.size}/$n")
    mismatchesA.take(3).forEach { println("  $it") }
    println("asContextElement mismatches=${mismatchesB.size}/$n")
    mismatchesB.take(3).forEach { println("  $it") }
}
