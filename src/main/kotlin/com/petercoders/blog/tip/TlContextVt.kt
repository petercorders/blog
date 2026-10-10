import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

// tip-p17 — kco/TlContext.kt 와 같은 흐름을 VT Dispatcher(newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()) 위에서 돌린다.
// 감싸지 않은 ThreadLocal 이 delay(10) 뒤 '남의 값'이 되는지(Default 결과, kco-e29), null 이 되는지 본다.
val tl = ThreadLocal<String>()

suspend fun probe(tag: String, expected: String, set: Boolean): String? {
    if (set) tl.set(expected)
    val before = Thread.currentThread()
    delay(10)                                   // 디스패치 경계
    val after = Thread.currentThread()
    val got = tl.get()
    return if (got == expected) null
    else "$tag before=${before.name.ifEmpty { "(unnamed)" }}#${before.threadId()} virtual=${before.isVirtual} after=${after.name.ifEmpty { "(unnamed)" }}#${after.threadId()} virtual=${after.isVirtual} tl=$got"
}

fun main() = runBlocking {
    val n = 64
    Executors.newVirtualThreadPerTaskExecutor().use { exec ->
        val vt = exec.asCoroutineDispatcher()
        val mismatchesA = ConcurrentLinkedQueue<String>()
        val mismatchesB = ConcurrentLinkedQueue<String>()
        (1..n).map { async(vt) {
            val r = probe("plain$it", "plain$it-value", set = true)
            if (r != null) mismatchesA += r
        } }.awaitAll()
        (1..n).map { async(vt + tl.asContextElement("ctx$it-value")) {
            val r = probe("ctx$it", "ctx$it-value", set = false)
            if (r != null) mismatchesB += r
        } }.awaitAll()
        val nulls = mismatchesA.count { it.endsWith("tl=null") }
        println("VT dispatcher plain ThreadLocal mismatches=${mismatchesA.size}/$n (tl=null ${nulls}, other value ${mismatchesA.size - nulls})")
        mismatchesA.take(3).forEach { println("  $it") }
        println("VT dispatcher asContextElement mismatches=${mismatchesB.size}/$n")
        mismatchesB.take(3).forEach { println("  $it") }
    }
}
