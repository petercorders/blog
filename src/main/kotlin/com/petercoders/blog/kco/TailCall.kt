import kotlinx.coroutines.*
suspend fun leaf(x: Int): Int { delay(1); return x + 1 }
suspend fun tail(x: Int): Int = leaf(x)          // 유일한 서스펜션 포인트가 꼬리 호출
suspend fun nonTail(x: Int): Int = leaf(x) + 1   // 반환값을 써야 하므로 꼬리 호출이 아님
fun main() = runBlocking { println(tail(1) + nonTail(1)) }
