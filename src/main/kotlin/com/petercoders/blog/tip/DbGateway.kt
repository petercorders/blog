import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

// tip-p27 — 3편 ② 「view 앞 대기열은 Hikari 지표에 보이지 않는다」의 요지 코드 DbGateway 를 그대로 돌린다.
// view(n=2) 앞에 100ms 블로킹 호출 4건을 동시에 던지면, 앞의 2건은 바로 칸을 얻고 뒤의 2건은 view 앞에서 약 100ms 기다린다.
// 그 기다림이 Micrometer Timer "db.view.wait" 에 남는지(count·max·mean) 를 찍는다. 3회는 JVM 을 따로 띄워 돌린다.
//   "$KC" -cp "$CO:$MC" DbGateway.kt -include-runtime -d dbg.jar && $J26/bin/java -cp "dbg.jar:$CO:$MC" DbGatewayKt
//   MC = micrometer-core·micrometer-commons·micrometer-observation 1.17.1 + HdrHistogram 2.2.2

// ---- 본문 요지 코드(그대로) ----
class DbGateway(private val db: CoroutineDispatcher, registry: MeterRegistry) {
    private val viewWait = Timer.builder("db.view.wait").register(registry)
    suspend fun <T> call(block: () -> T): T {
        val queuedAt = System.nanoTime()
        return withContext(db) { viewWait.record(System.nanoTime() - queuedAt, TimeUnit.NANOSECONDS); block() }
    }
}
// ---- 여기까지 ----

fun main() = runBlocking {
    fun jarOf(c: Class<*>) = c.protectionDomain.codeSource.location.path.substringAfterLast('/')
    println("# runtime: java ${System.getProperty("java.runtime.version")} | ${jarOf(Job::class.java)} | ${jarOf(Timer::class.java)}")
    val registry = SimpleMeterRegistry()
    val db = Dispatchers.IO.limitedParallelism(2)
    val gateway = DbGateway(db, registry)
    val t0 = System.nanoTime()
    val ends = (1..4).map { i ->
        async(Dispatchers.Default) { gateway.call { Thread.sleep(100); i }; (System.nanoTime() - t0) / 1_000_000 }
    }.awaitAll()
    val timer = registry.get("db.view.wait").timer()
    println("view n=2, calls=4 x Thread.sleep(100)")
    println("call end ms (from launch): ${ends.joinToString()}")
    println("db.view.wait count=${timer.count()} max=%.1fms mean=%.1fms total=%.1fms".format(
        timer.max(TimeUnit.MILLISECONDS), timer.mean(TimeUnit.MILLISECONDS), timer.totalTime(TimeUnit.MILLISECONDS)))
}
