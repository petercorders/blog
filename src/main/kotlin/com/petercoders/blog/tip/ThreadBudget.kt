import kotlinx.coroutines.*
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger

// tip-p12 — Default·IO·IO.limitedParallelism(n) 를 동시에 포화시키면 platform thread 총량이 15 + 64 + Σn 상한에 닿는가.
//   DEF   : Default 에 CPU spin 15개
//   IO    : Dispatchers.IO 에 Thread.sleep 100개(한도 64 초과)
//   LP10  : IO.limitedParallelism(10) 에 sleep 30개
//   LP100 : IO.limitedParallelism(100) 에 sleep 150개
//   ALL   : 위 넷을 동시에
// 1초 뒤 ThreadMXBean threadCount/peak 와 DefaultDispatcher-worker 이름 수, 그룹별 동시 실행 수를 찍는다. 케이스마다 JVM 을 새로 띄운다.
class Group(val name: String) { val now = AtomicInteger(); val max = AtomicInteger()
    fun enter() { max.accumulateAndGet(now.incrementAndGet()) { a, b -> maxOf(a, b) } }
    fun exit() { now.decrementAndGet() } }

@Volatile var sink = 0L

fun main(args: Array<String>) = runBlocking {
    val case = args[0]
    val mx = ManagementFactory.getThreadMXBean()
    val base = mx.threadCount
    val groups = mutableListOf<Group>()
    val holdMs = 2_000L
    val job = launch {
        fun spawn(g: Group, d: CoroutineDispatcher, count: Int, cpu: Boolean) { groups += g; repeat(count) { launch(d) {
            g.enter()
            if (cpu) { val end = System.nanoTime() + holdMs * 1_000_000; var x = 0L; while (System.nanoTime() < end) x++; sink += x }
            else Thread.sleep(holdMs)
            g.exit()
        } } }
        if (case == "DEF" || case == "ALL") spawn(Group("Default(spin)"), Dispatchers.Default, 15, cpu = true)
        if (case == "IO" || case == "ALL") spawn(Group("IO(sleep)"), Dispatchers.IO, 100, cpu = false)
        if (case == "LP10" || case == "ALL") spawn(Group("IO.lp(10)"), Dispatchers.IO.limitedParallelism(10), 30, cpu = false)
        if (case == "LP100" || case == "ALL") spawn(Group("IO.lp(100)"), Dispatchers.IO.limitedParallelism(100), 150, cpu = false)
    }
    // 1초 뒤 측정. Default 15개가 spin 중이라 측정은 별도 platform thread 에서 한다.
    val report = Thread.ofPlatform().name("probe").start {
        Thread.sleep(1_000)
        val workers = Thread.getAllStackTraces().keys.count { it.name.startsWith("DefaultDispatcher-worker") }
        val running = groups.joinToString("  ") { "${it.name} now=${it.now.get()}" }
        println("%-6s at 1s: threadCount=%3d peak=%3d (base before launch=%d)  DefaultDispatcher-worker=%3d  | %s"
            .format(case, mx.threadCount, mx.peakThreadCount, base, workers, running))
    }
    job.join(); report.join()
    println("%-6s done: peak=%3d  maxConcurrent: %s".format(case, mx.peakThreadCount, groups.joinToString("  ") { "${it.name}=${it.max.get()}" }))
}
