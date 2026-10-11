import kotlinx.coroutines.*
import java.util.concurrent.Executors

// tip-p30 — 3편 ① 「진입점마다 블로킹이 남는 스레드가 다르다」·④ 「Virtual Thread Dispatcher는 …」:
// kotlinx.coroutines.DefaultExecutor 가 막히면 어느 Dispatcher 위의 delay·withTimeout 이 같이 밀리는가.
//
// 막는 쪽: Dispatchers.Unconfined 에서 delay(10) 뒤 Thread.sleep(2000). Unconfined 는 Delay 를 구현하지 않아
//          delay 가 끝나면 DefaultExecutor 스레드에서 이어 돌고, 그 스레드가 2초를 잔다(tip-p20·kco-e13 과 같은 방식).
// 재는 쪽: 막기 시작하고 100ms 뒤, 각 Dispatcher 위에서 (1) delay(100) 이 걸린 시간 (2) withTimeout(100) { delay(1000) } 이
//          TimeoutCancellationException 으로 끝난 시간. 막지 않은 기준선(alone)도 같이 잰다.
// 케이스(인자 하나, 케이스마다 JVM 을 따로 띄운다):
//   VT    = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()   (ScheduledExecutorService 가 아니다)
//   VTLP  = VT.limitedParallelism(4)                                               (LimitedDispatcher 는 원본의 Delay 를 쓴다)
//   IO    = Dispatchers.IO                                                         (대조: Delay 미구현 → DefaultDelay)
//   SCHED = Executors.newScheduledThreadPool(1).asCoroutineDispatcher()            (대조: 타이머를 그 executor 가 직접 잰다)
// 근거 소스: kotlinx.coroutines 1.10.2 jvm/src/Executors.kt L139-159, common/src/internal/LimitedDispatcher.kt L26,
//          common/src/Delay.kt L149.
//   "$KC" -cp "$CO" VtDelayTimer.kt -include-runtime -d vdt.jar
//   for c in VT VTLP IO SCHED; do $J26/bin/java -cp "vdt.jar:$CO" VtDelayTimerKt $c; done

private fun Thread.label() = "${name.ifEmpty { "(unnamed)" }}#${threadId()} virtual=$isVirtual"

private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

private suspend fun delayProbe(d: CoroutineDispatcher): String = withContext(d) {
    val before = Thread.currentThread().label()
    val t0 = System.nanoTime()
    delay(100)
    "delay(100)=${ms(t0)}ms before=$before after=${Thread.currentThread().label()}"
}

private suspend fun timeoutProbe(d: CoroutineDispatcher): String {
    val t0 = System.nanoTime()
    val r = runCatching { withContext(d) { withTimeout(100) { delay(1000) } } }
    return "withTimeout(100){delay(1000)}=${ms(t0)}ms ${r.exceptionOrNull()?.javaClass?.simpleName ?: "no exception"}"
}

// DefaultExecutor 를 2초 막는 동안 probe 를 돌린다
private suspend fun CoroutineScope.whileDefaultExecutorBlocked(probe: suspend () -> String): String {
    var blockerThread = "?"
    val blocker = launch(Dispatchers.Unconfined) {
        delay(10)
        blockerThread = Thread.currentThread().name
        Thread.sleep(2000)
    }
    Thread.sleep(100) // 막는 쪽이 DefaultExecutor 위에서 잠들 때까지 기다린다
    val result = probe()
    blocker.join()
    return "$result | blocker slept on [$blockerThread]"
}

fun main(args: Array<String>) = runBlocking {
    val case = args.firstOrNull() ?: "VT"
    fun jarOf(c: Class<*>) = c.protectionDomain.codeSource.location.path.substringAfterLast('/')
    println("# runtime: java ${System.getProperty("java.runtime.version")} | ${jarOf(Job::class.java)} | case=$case")
    val vt = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    val sched = Executors.newScheduledThreadPool(1) { r -> Thread(r, "sched-1") }.asCoroutineDispatcher()
    val d: CoroutineDispatcher = when (case) {
        "VT" -> vt
        "VTLP" -> vt.limitedParallelism(4)
        "IO" -> Dispatchers.IO
        "SCHED" -> sched
        else -> error("unknown case $case")
    }
    println("$case dispatcher=$d class=${d.javaClass.name}")
    println("$case alone   : ${delayProbe(d)}")
    println("$case alone   : ${timeoutProbe(d)}")
    println("$case blocked : ${whileDefaultExecutorBlocked { delayProbe(d) }}")
    println("$case blocked : ${whileDefaultExecutorBlocked { timeoutProbe(d) }}")
    vt.close()
    sched.close()
}
