import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

// withTimeout(100) 아래에서 다섯 종류의 블로킹/서스펜션이 실제로 언제 끝나는지 잰다.
val server = ServerSocket(0)

fun newClient(): java.io.InputStream {
    val s = Socket("127.0.0.1", server.localPort)
    Thread.ofPlatform().daemon(true).start { Thread.sleep(1500); runCatching { s.close() } }   // 1.5초 뒤 감시 스레드가 소켓을 닫는다
    return s.getInputStream()
}

suspend fun timed(name: String, block: suspend () -> Unit) {
    var last: Throwable? = null
    val ms = measureTimeMillis {
        try { withTimeout(100) { block() } } catch (e: Exception) { last = e }
    }
    println("%-44s %5d ms  %s".format(name, ms, last?.javaClass?.simpleName))
}

fun main() = runBlocking {
    val vt = Executors.newVirtualThreadPerTaskExecutor()
    val vtDispatcher = vt.asCoroutineDispatcher()
    timed("IO: sleep(3000)")                     { withContext(Dispatchers.IO) { Thread.sleep(3000) } }
    timed("IO: runInterruptible{sleep(3000)}")   { withContext(Dispatchers.IO) { runInterruptible { Thread.sleep(3000) } } }
    timed("IO: delay(3000)")                     { withContext(Dispatchers.IO) { delay(3000) } }
    timed("IO: runInterruptible{socket.read()}") { withContext(Dispatchers.IO) { runInterruptible { newClient().read() } } }
    timed("VT: runInterruptible{socket.read()}") { withContext(vtDispatcher) { runInterruptible { newClient().read() } } }
    vt.shutdown()
}
