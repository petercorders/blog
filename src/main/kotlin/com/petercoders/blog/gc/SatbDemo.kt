package com.petercoders.blog.gc

import java.util.concurrent.atomic.AtomicBoolean

/**
 * SATB 프리라이트 배리어가 도는 동안의 동시 마킹 사이클을 관찰한다.
 *
 * 마킹 중에도 뮤테이터가 참조 필드를 계속 덮어써서 옛 값이 SATB 버퍼로 들어가게 만든다.
 *
 * 실행:
 *   kotlinc SatbDemo.kt -include-runtime -d satb.jar
 *   java -Xmx512m -XX:+ExplicitGCInvokesConcurrent \
 *     -Xlog:gc,gc+phases=debug,gc+marking=debug \
 *     -cp satb.jar com.petercoders.blog.gc.SatbDemoKt
 *
 * 볼 것: Concurrent Mark From Roots 시간, Pause Remark 안의 Finalize Marking.
 */

private class Node {
    @JvmField var next: Node? = null
    @JvmField val pad = ByteArray(200)
}

fun main() {
    val heads = Array(20_000) {                 // 라이브 그래프: 체인 20,000개 × 노드 50개
        val head = Node()
        var cur = head
        repeat(50) { val n = Node(); cur.next = n; cur = n }
        head
    }

    val stop = AtomicBoolean()
    val mutator = Thread {                      // 마킹 중에도 계속 참조 필드를 덮어쓴다
        var i = 0
        while (!stop.get()) {
            val h = heads[i % heads.size]
            val second = h.next
            h.next = second?.next               // 옛 값(second)이 SATB 버퍼로 들어간다
            i++
            if (i and 0xFFFF == 0) Thread.onSpinWait()
        }
    }
    mutator.isDaemon = true
    mutator.start()

    repeat(3) { System.gc() }                   // ExplicitGCInvokesConcurrent 면 동시 마킹 사이클
    stop.set(true)
    mutator.join()
}
