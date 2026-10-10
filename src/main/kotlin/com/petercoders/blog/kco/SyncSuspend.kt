import kotlinx.coroutines.*

// kco-e27 — synchronized 블록 안의 suspension point 는 컴파일러가 거절한다(이 파일은 컴파일되지 않는 것이 정상이다)
val lock = Any()
suspend fun guarded() { synchronized(lock) { delay(1) } }
fun main() = runBlocking { guarded() }
