import kotlinx.coroutines.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// kco-e30 — kotlin.concurrent.withLock 블록 안의 suspension point 도 synchronized 와 같은 검사에 걸린다(이 파일은 컴파일되지 않는 것이 정상이다)
val rl = ReentrantLock()
suspend fun guarded() { rl.withLock { delay(1) } }
fun main() = runBlocking { guarded() }
