import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

// kco-e13 — Spring 이 suspend 핸들러를 시작하는 Dispatchers.Unconfined 에서 delay 뒤의 블로킹이 어디로 가나.
// 타이머 프로브: 같은 시간에 Default 위 delay(10) 50번의 지연 오차(실제 경과 - 10ms)를 잰다.
suspend fun probe(label: String): Pair<Long, Long> = coroutineScope {
    val lags = (1..50).map {
        val t = System.nanoTime(); delay(10); (System.nanoTime() - t) / 1_000_000 - 10
    }
    lags.max() to lags.sorted()[lags.size / 2]
}

fun main() = runBlocking {
    repeat(3) { r ->
        println("=== run ${r + 1} ===")
        val (baseMax, baseMed) = withContext(Dispatchers.Default) { probe("baseline") }
        println("timer probe, no blocking   : delay(10) x50 on Default  maxLag=$baseMax ms  medianLag=$baseMed ms")

        val before = ConcurrentHashMap<String, Int>(); val after = ConcurrentHashMap<String, Int>()
        var timer: Pair<Long, Long>? = null
        val ms = measureTimeMillis {
            coroutineScope {
                val p = launch(Dispatchers.Default) { timer = probe("during") }
                (1..20).map {
                    launch(Dispatchers.Unconfined) {
                        before.merge(Thread.currentThread().name, 1, Int::plus)
                        delay(1)
                        after.merge(Thread.currentThread().name, 1, Int::plus)
                        Thread.sleep(100)
                    }
                }.joinAll()
                p.join()
            }
        }
        println("Unconfined x20: delay(1) then Thread.sleep(100)            total=$ms ms  before=$before  after=$after")
        println("timer probe, during blocking: delay(10) x50 on Default  maxLag=${timer!!.first} ms  medianLag=${timer!!.second} ms")

        val after2 = ConcurrentHashMap<String, Int>(); val inIo = ConcurrentHashMap.newKeySet<String>()
        val ms2 = measureTimeMillis {
            (1..20).map {
                launch(Dispatchers.Unconfined) {
                    delay(1)
                    after2.merge(Thread.currentThread().name, 1, Int::plus)
                    withContext(Dispatchers.IO) { inIo += Thread.currentThread().name; Thread.sleep(100) }
                }
            }.joinAll()
        }
        println("Unconfined x20: delay(1) then withContext(IO){sleep(100)}  total=$ms2 ms  after=$after2  ioWorkers=${inIo.size}")
    }
}
