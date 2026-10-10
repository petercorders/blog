import kotlinx.coroutines.*
import java.util.concurrent.locks.ReentrantLock

// kco-e30 — lock()/unlock() 을 직접 부르면 같은 suspension point 가 검사를 지나 컴파일된다(kco-e28 이 이 모양이다)
val rl = ReentrantLock()
suspend fun guarded() { rl.lock(); try { delay(1) } finally { rl.unlock() } }
fun main() = runBlocking { guarded(); println("compiled and ran: lock()/delay(1)/unlock() on one thread") }
