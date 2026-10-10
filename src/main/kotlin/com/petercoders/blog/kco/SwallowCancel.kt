import kotlinx.coroutines.*

// kco-e21 — catch (e: Exception) / runCatching 이 CancellationException 을 삼키면 재시도 루프가 취소 뒤에도 돈다
fun main() = runBlocking {
    val t0 = System.nanoTime()
    fun ms() = (System.nanoTime() - t0) / 1_000_000

    val a = launch { repeat(5) { i -> try { delay(100); println("  [catch] ok $i t=${ms()}") } catch (e: Exception) { println("  [catch] retry $i ${e.javaClass.simpleName} t=${ms()}") } }; println("  [catch] loop done t=${ms()}") }
    delay(150); println("cancel [catch] t=${ms()}"); a.cancel(); a.join()

    val b = launch { repeat(5) { i -> runCatching { delay(100) }.onFailure { println("  [runCatching] retry $i ${it.javaClass.simpleName} t=${ms()}") } }; println("  [runCatching] loop done t=${ms()}") }
    delay(150); println("cancel [runCatching] t=${ms()}"); b.cancel(); b.join()

    val c = launch { repeat(5) { i -> try { delay(100) } catch (e: Exception) { ensureActive(); println("  [ensureActive] retry $i t=${ms()}") } }; println("  [ensureActive] loop done t=${ms()}") }
    delay(150); println("cancel [ensureActive] t=${ms()}"); c.cancel(); c.join()
    println("[ensureActive] isCancelled=${c.isCancelled}  t=${ms()}")
}
