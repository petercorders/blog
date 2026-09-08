package com.petercoders.blog.gc

import java.util.concurrent.CountDownLatch

/**
 * 스택 스캔 비용이 스레드 수에 따라 어떻게 움직이는지 G1 과 ZGC 에서 비교한다.
 *
 * 실행:
 *   kotlinc StackRoots.kt -include-runtime -d stack.jar
 *   for n in 50 500 2000; do
 *     java -Xmx1g -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent \
 *       -Xlog:gc,gc+phases=trace,safepoint -jar stack.jar $n
 *     java -Xmx1g -XX:+UseZGC \
 *       -Xlog:gc,gc+phases=debug,safepoint -jar stack.jar $n
 *   done
 *
 * 볼 것: G1 은 Thread Roots (ms) 와 At safepoint 가 함께 늘고,
 *        ZGC 는 Pause Mark Start 가 그대로인 대신 Concurrent Mark Roots 가 는다.
 */
private val done = CountDownLatch(1)

/** depth 만큼 프레임을 쌓고, 각 프레임에 참조 지역변수를 남긴 채 블록된다. */
private fun dive(depth: Int, keep: Any?, ready: CountDownLatch) {
    val local: Any = arrayOf(keep)
    if (depth > 0) { dive(depth - 1, local, ready); return }
    ready.countDown()
    done.await()
    @Suppress("SENSELESS_COMPARISON")
    if (local == null) println()
}

fun main(args: Array<String>) {
    val n = args[0].toInt()
    val depth = 300
    val ready = CountDownLatch(n)
    repeat(n) { i ->
        Thread(null, { dive(depth, null, ready) }, "w$i", 512 * 1024).apply {
            isDaemon = true
            start()
        }
    }
    ready.await()
    repeat(3) { System.gc() }
    done.countDown()
}
