import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket

// 세 종류의 블로킹이 Dispatchers.Default 워커를 어떤 상태·톱 프레임으로 잡는지 jcmd 로 본다.
fun main() = runBlocking {
    val server = ServerSocket(0)                                   // 아무것도 안 보내는 로컬 서버(accept 안 함, backlog 로 연결만 성립)
    println(ProcessHandle.current().pid())
    launch(Dispatchers.Default) { Thread.sleep(30_000) }                                          // (a) sleep
    launch(Dispatchers.Default) { Socket("127.0.0.1", server.localPort).getInputStream().read() }   // (b) 소켓 read
    launch(Dispatchers.Default) { runBlocking { delay(30_000) } }                                 // (c) 중첩 runBlocking
    delay(40_000)
}
