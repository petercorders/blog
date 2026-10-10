import kotlinx.coroutines.*
import reactor.blockhound.BlockHound

// kco-e24 — BlockHound + kotlinx-coroutines-debug 통합: Default worker 위 Thread.sleep 을 잡는가
fun main() {
    BlockHound.install()   // ServiceLoader 로 kotlinx-coroutines-debug 의 CoroutinesBlockHoundIntegration 도 함께 읽는다
    runBlocking {
        val onDefault = runCatching { withContext(Dispatchers.Default) { Thread.sleep(10) } }.exceptionOrNull()
        println("Default + Thread.sleep(10): ${onDefault?.let { "${it.javaClass.name}: ${it.message}" } ?: "no error"}")
        val onIo = runCatching { withContext(Dispatchers.IO) { Thread.sleep(10) } }.exceptionOrNull()
        println("IO      + Thread.sleep(10): ${onIo?.let { "${it.javaClass.name}: ${it.message}" } ?: "no error"}")
    }
}
