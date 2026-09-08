package com.petercoders.blog.gc

/**
 * G1 리멤버드 셋의 카드 집합이 InlinePtr → Array Of Cards → Howl → Full 로 갈아타는 것을 관찰한다.
 *
 * Old 리전 다수가 Young 리전 소수를 집중적으로 가리키게 만들어 coarsening 을 강제한다.
 *
 * 실행:
 *   kotlinc RemSetCoarsen.kt -include-runtime -d rem.jar
 *   java -Xms1g -Xmx1g -XX:+UseG1GC \
 *     -Xlog:gc,gc+init,gc+phases=debug,gc+remset=debug,gc+refine=debug \
 *     -jar rem.jar 2000000 100000
 *
 * 볼 것: Coarsening (recent) 의 Inline->AoC / AoC->Howl / Howl->Full 카운터, Merged Full, Scanned Cards.
 */

/** 64바이트짜리 old 객체: 카드 한 장(512B)에 8개가 들어간다. */
private class Holder {
    @JvmField var ref: Any? = null
    @JvmField var p0: Long = 0
    @JvmField var p1: Long = 0
    @JvmField var p2: Long = 0
    @JvmField var p3: Long = 0
    @JvmField var p4: Long = 0
    @JvmField var p5: Long = 0
}

fun main(args: Array<String>) {
    val holders = args[0].toInt()          // 예: 2_000_000 → 약 128MB
    val targets = args[1].toInt()          // 예: 100_000  → 참조가 몰릴 young 객체 수

    val hs = Array(holders) { Holder() }
    System.gc(); System.gc()               // Holder 전부를 Old 로 보낸다

    repeat(3) {
        val ys = Array<Any>(targets) { Any() }               // young 리전 소수에 몰린 대상
        for (i in 0 until holders) hs[i].ref = ys[i % targets]   // Old→Young 참조 대량 생성
        Thread.sleep(500)                  // 리파인 스레드가 더티 카드를 리멤버드 셋으로 옮길 시간
        var junk: ByteArray? = null
        repeat(400) { junk = ByteArray(1 shl 20) }           // Eden 을 채워 Young GC 유발
        if (junk == null) println()
    }
}
