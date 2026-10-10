import kotlinx.coroutines.*
import kotlinx.coroutines.debug.DebugProbes

// kco-e23 — -Dkotlinx.coroutines.debug 의 스레드 이름, stack trace recovery, DebugProbes.dumpCoroutines()
suspend fun loadOrder(): Int = withContext(Dispatchers.IO) { delay(1); throw IllegalStateException("db down") }
suspend fun handle(): Int = loadOrder() + 1

@OptIn(ExperimentalCoroutinesApi::class)   // DebugProbes 는 1.10.2 에서 @ExperimentalCoroutinesApi
fun main(args: Array<String>) = runBlocking {
    when (args.firstOrNull()) {
        "names" -> {
            println("kotlinx.coroutines.debug=${System.getProperty("kotlinx.coroutines.debug")}")
            launch(Dispatchers.Default) { println("unnamed : ${Thread.currentThread().name}") }.join()
            launch(Dispatchers.Default + CoroutineName("excel")) { println("named   : ${Thread.currentThread().name}") }.join()
        }
        "trace" -> {
            println("kotlinx.coroutines.debug=${System.getProperty("kotlinx.coroutines.debug")}")
            try { handle() } catch (e: IllegalStateException) {
                val lines = e.stackTraceToString().lines().filter { it.isNotBlank() }
                println("frames=${e.stackTrace.size}  mentions handle()=${lines.any { "handle" in it }}  mentions main=${lines.any { "DebugModeKt\$main" in it }}")
                lines.take(12).forEach { println("  $it") }
            }
        }
        "probes" -> {
            DebugProbes.install()
            val jobs = listOf(
                launch(Dispatchers.Default + CoroutineName("excel")) { delay(Long.MAX_VALUE) },
                launch(Dispatchers.IO + CoroutineName("warmup")) { awaitCancellation() },
            )
            delay(100)
            DebugProbes.dumpCoroutines(System.out)
            jobs.forEach { it.cancel() }
            DebugProbes.uninstall()
        }
    }
}
