/**
 * 카드 테이블 false sharing. 자바 코드에는 공유 상태가 없는데 카드 테이블이 공유 상태가 된다.
 *
 * 인자는 스레드별 배열 사이의 힙 거리(패딩 바이트)다.
 *   0     — 네 배열이 같은 카드(512B) 안
 *   1024  — 카드는 다르지만 같은 캐시라인
 *   65536 — 캐시라인이 다르다
 *
 * 실행:
 *   kotlinc CardFalseSharing.kt -include-runtime -d fs.jar
 *   for pad in 0 1024 65536; do
 *     java -XX:+UseSerialGC -Xmn256m -jar fs.jar $pad
 *     java -XX:+UseSerialGC -XX:+UseCondCardMark -Xmn256m -jar fs.jar $pad
 *   done
 */
private const val THREADS = 4
private const val ITERS = 500_000_000

fun main(args: Array<String>) {
    val padBytes = args[0].toInt()
    // 배열과 패딩을 번갈아 할당해야 패딩이 배열 사이를 힙에서 벌린다.
    // 배열을 먼저 다 만들면 네 개가 붙어버려 padBytes 가 아무 효과도 내지 못한다.
    val arrays = arrayOfNulls<Array<Any?>>(THREADS)
    val pads = arrayOfNulls<Any>(THREADS)
    for (t in 0 until THREADS) {
        arrays[t] = arrayOfNulls(16)                         // 참조 16칸 = 카드 한 장(512B) 안
        if (padBytes > 0) pads[t] = ByteArray(padBytes)
    }
    val values = Array<Any>(THREADS) { Any() }

    repeat(3) { round ->
        val ts = (0 until THREADS).map { t ->
            val a = arrays[t]!!
            val v = values[t]
            Thread { for (i in 0 until ITERS) a[i and 15] = v }   // 참조 대입 → 카드 마킹
        }
        val start = System.nanoTime()
        ts.forEach { it.start() }
        ts.forEach { it.join() }
        val ns = System.nanoTime() - start
        println("pad=%d round=%d  %d ms  (%.1f M stores/s)"
            .format(padBytes, round, ns / 1_000_000, THREADS.toDouble() * ITERS / ns * 1000))
    }
    if (pads[0] === pads[1]) println()          // 패딩을 살려둔다
}
