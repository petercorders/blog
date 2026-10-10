import kotlinx.coroutines.*
import reactor.blockhound.BlockHound
import java.util.concurrent.Executors

// tip-p25 — kco/BlockHoundCheck.kt(kco-e24) 에 VT Dispatcher 케이스를 더한다.
// BlockHound + kotlinx-coroutines-debug 통합이 Default·IO·VT Dispatcher 위 Thread.sleep 중 무엇을 잡는가.
fun main() {
    BlockHound.install()   // ServiceLoader 로 kotlinx-coroutines-debug 의 CoroutinesBlockHoundIntegration 도 함께 읽는다
    Executors.newVirtualThreadPerTaskExecutor().use { exec ->
        val vt = exec.asCoroutineDispatcher()
        runBlocking {
            for ((name, d) in listOf("Default" to Dispatchers.Default, "IO" to Dispatchers.IO, "VT Dispatcher" to vt,
                                     "Default.limitedParallelism(2)" to Dispatchers.Default.limitedParallelism(2))) {
                val err = runCatching { withContext(d) { Thread.sleep(10) } }.exceptionOrNull()
                println("%-30s + Thread.sleep(10): %s".format(name, err?.let { "${it.javaClass.name}: ${it.message}" } ?: "no error"))
            }
        }
    }
}
