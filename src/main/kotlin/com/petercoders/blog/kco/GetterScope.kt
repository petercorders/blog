import kotlinx.coroutines.*

// kco-e20 — (a) getter 로 접근할 때마다 새 SupervisorJob 을 만드는 CoroutineScope 구현은 cancel() 이 닿지 않는다
//           (b) runBlocking { CoroutineScope(IO).launch { … } } 는 launch 만 시작하고 즉시 돌아온다
class GetterSvc : CoroutineScope {
    override val coroutineContext get() = Dispatchers.Default + SupervisorJob()   // 접근할 때마다 새 Job
}
class FieldSvc : CoroutineScope {
    override val coroutineContext = Dispatchers.Default + SupervisorJob()        // 필드 하나
}

fun main() {
    runBlocking {
        val g = GetterSvc(); val gj = List(3) { g.launch { delay(Long.MAX_VALUE) } }
        g.cancel(); delay(100)
        println("(a) getter scope: after cancel() + 100 ms isActive=${gj.map { it.isActive }}")
        val f = FieldSvc(); val fj = List(3) { f.launch { delay(Long.MAX_VALUE) } }
        f.cancel(); delay(100)
        println("(a) field  scope: after cancel() + 100 ms isActive=${fj.map { it.isActive }}")
        gj.forEach { it.cancel() }
    }

    val t0 = System.nanoTime()
    fun ms() = (System.nanoTime() - t0) / 1_000_000
    runBlocking { CoroutineScope(Dispatchers.IO).launch { delay(500); println("(b) done t=${ms()} ms") } }
    println("(b) runBlocking returned t=${ms()} ms")
    Thread.sleep(800)
    println("(b) main exits t=${ms()} ms")
}
