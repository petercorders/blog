import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

// 여섯 종류의 블로킹이 스레드를 어떤 상태·톱 프레임으로 잡는지 jcmd Thread.dump_to_file 로 본다.
// stdout 은 (e) 케이스가 읽히지 않는 파이프에 쓰므로, PID 와 진단 줄은 stderr 로 쓴다.
//   java -cp bw.jar:$CO BlockingWorkersKt 2> pid.txt | sleep 60
fun main() = runBlocking {
    val server = ServerSocket(0)                                   // 아무것도 안 보내는 로컬 서버(accept 안 함, backlog 로 연결만 성립)
    System.err.println(ProcessHandle.current().pid())
    launch(Dispatchers.Default) { Thread.sleep(30_000) }                                          // (a) sleep
    launch(Dispatchers.Default) { Socket("127.0.0.1", server.localPort).getInputStream().read() }   // (b) 소켓 read, 타임아웃 없음
    launch(Dispatchers.Default) { runBlocking { delay(30_000) } }                                 // (c) 중첩 runBlocking
    launch(Dispatchers.Default) {                                                                 // (d) 소켓 read, soTimeout 30초
        Socket("127.0.0.1", server.localPort).apply { soTimeout = 30_000 }.getInputStream().read()
    }
    launch(Dispatchers.Default) { var i = 0L; while (true) println("line ${i++} " + "x".repeat(100)) }   // (e) 아무도 안 읽는 stdout 파이프에 println
    // (f) VT Dispatcher 위 소켓 read. newVirtualThreadPerTaskExecutor 와 같되 덤프에서 찾으려고 이름만 붙인다.
    val vt = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("vtd-", 0).factory()).asCoroutineDispatcher()
    launch(vt) { Socket("127.0.0.1", server.localPort).getInputStream().read() }
    delay(2_000)
    System.err.println("Dispatchers.Default.toString() = ${Dispatchers.Default}")   // 비공식 스케줄러 상태 문자열(1회)
    delay(38_000)
}
