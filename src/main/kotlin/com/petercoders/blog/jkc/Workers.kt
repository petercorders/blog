import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
fun main() = runBlocking {
    val def = ConcurrentHashMap.newKeySet<String>(); val io = ConcurrentHashMap.newKeySet<String>()
    val jobs = (1..1000).map { launch(Dispatchers.Default) { def += Thread.currentThread().name; delay(50) } } +
               (1..1000).map { launch(Dispatchers.IO) { io += Thread.currentThread().name; Thread.sleep(50) } }
    jobs.joinAll()
    val cores = Runtime.getRuntime().availableProcessors()
    println("cores=$cores  Default workers=${def.size}  IO workers=${io.size}  shared=${(def intersect io).size}  total=${(def + io).size}")
    println("sample: ${def.minOf { it }}")
}
