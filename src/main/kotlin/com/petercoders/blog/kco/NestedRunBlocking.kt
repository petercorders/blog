import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

// kco-e11 — 중첩 runBlocking 이 worker 를 쥔 채 기다리는지, 대조군이 결론을 가르도록 다시 짠 판.
fun elapsed(t0: Long) = (System.nanoTime() - t0) / 1_000_000

// 같은 limitedParallelism(1) 위에서 first 를 먼저 올리고, 바로 뒤에 올린 형제가 언제 시작하는지 잰다.
suspend fun sibling(label: String, first: suspend () -> Unit) = coroutineScope {
    val one = Dispatchers.Default.limitedParallelism(1)
    val t0 = System.nanoTime()
    launch(one) { first() }
    launch(one) { println("$label: sibling started at ${elapsed(t0)} ms on ${Thread.currentThread().name}") }
}

// ---- bol.com 'Coroutine Gotchas – Bridging the gap…' 구조를 그대로 옮긴 변형 ----
val context = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
suspend fun asyncTask(task: String, duration: Long) {
    println("  Started $task call on ${Thread.currentThread().name}")
    delay(duration)
    println("  Ended $task call on ${Thread.currentThread().name}")
}
fun notSoAsyncTask(task: String, duration: Long) = runBlocking { asyncTask(task, duration) }
fun blockingAsyncTask(task: String, duration: Long) = runBlocking(context) { asyncTask(task, duration) }

fun main(args: Array<String>) {
    if (args.firstOrNull() == "deadlock") return deadlock()
    runBlocking {
        repeat(3) { r ->
            sibling("A run${r + 1} nested runBlocking{delay(300)}") {
                runBlocking { if (r == 0) println("  inner runBlocking on ${Thread.currentThread().name}"); delay(300) }
            }
            sibling("B run${r + 1} plain delay(300)            ") { delay(300) }
        }
    }
    println("bol V1 runBlocking(context){ 3 x launch{ asyncTask(1000) } }")
    println("bol V1 Took: ${measureTimeMillis { runBlocking(context) { coroutineScope {
        launch { asyncTask("slow", 1000) }; launch { asyncTask("another slow", 1000) }; launch { asyncTask("yet another slow", 1000) } } } }}ms")
    println("bol V2 runBlocking(context){ 3 x launch{ notSoAsyncTask(1000) = runBlocking{…} } }")
    println("bol V2 Took: ${measureTimeMillis { runBlocking(context) { coroutineScope {
        launch { notSoAsyncTask("slow", 1000) }; launch { notSoAsyncTask("another slow", 1000) }; launch { notSoAsyncTask("yet another slow", 1000) } } } }}ms")
    context.close()
}

// bol V3: 바깥과 같은 2스레드 context 를 지정한 중첩 runBlocking. 3초 뒤 자기 자신에게 jcmd Thread.print 를 걸고 내려간다.
fun deadlock() {
    Thread {
        Thread.sleep(3000)
        println("bol V3: no progress after 3000 ms — jcmd ${ProcessHandle.current().pid()} Thread.print (pool-/main 스레드만 발췌)")
        val jcmd = System.getProperty("java.home") + "/bin/jcmd"
        val out = ProcessBuilder(jcmd, ProcessHandle.current().pid().toString(), "Thread.print")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        out.split("\n\n").filter { it.startsWith("\"pool-") || it.startsWith("\"main\"") }
            .forEach { block -> block.lines().take(14).forEach { println("  | $it") }; println("  | …") }
        exitProcess(0)
    }.apply { isDaemon = true }.start()
    println("bol V3 runBlocking(context){ 3 x launch{ blockingAsyncTask(1000) = runBlocking(context){…} } }")
    runBlocking(context) { coroutineScope {
        launch { blockingAsyncTask("slow", 1000) }; launch { blockingAsyncTask("another slow", 1000) }; launch { blockingAsyncTask("yet another slow", 1000) } } }
    println("bol V3 finished (교착 없음)")
    exitProcess(0)
}
