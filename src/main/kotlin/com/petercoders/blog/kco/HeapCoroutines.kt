import kotlinx.coroutines.*

// kco-e22 — suspend 중인 Coroutine 은 스레드 덤프가 아니라 Heap 에 있다. 자기 자신에게 jcmd 를 건다.
fun jcmd(vararg a: String): String {
    val pid = ProcessHandle.current().pid().toString()
    return ProcessBuilder(listOf(System.getProperty("java.home") + "/bin/jcmd", pid) + a)
        .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
}

fun main(args: Array<String>) = runBlocking {
    val n = args.firstOrNull()?.toInt() ?: 100_000
    repeat(n) { launch(Dispatchers.Default) { delay(Long.MAX_VALUE) } }
    delay(500)
    println("launched $n coroutines, all suspended in delay(Long.MAX_VALUE); pid=${ProcessHandle.current().pid()}")
    println("$ jcmd <pid> GC.class_histogram | grep -E 'HeapCoroutinesKt|StandaloneCoroutine|CancellableContinuationImpl|DispatchedContinuation'")
    jcmd("GC.class_histogram").lines().filter { Regex("HeapCoroutinesKt|StandaloneCoroutine|CancellableContinuationImpl|DispatchedContinuation").containsMatchIn(it) }.forEach { println(it) }
    val f = java.io.File.createTempFile("kco-e22-", ".txt"); f.delete()
    jcmd("Thread.dump_to_file", f.path)
    val dump = f.readLines()
    println("$ jcmd <pid> Thread.dump_to_file ${f.name}")
    println("threads in dump=${dump.count { it.startsWith("#") }}  DefaultDispatcher-worker threads=${dump.count { it.startsWith("#") && it.contains("DefaultDispatcher-worker") }}  lines mentioning HeapCoroutinesKt=${dump.count { it.contains("HeapCoroutinesKt") }}")
    dump.filter { it.contains("HeapCoroutinesKt") }.forEach { println("  | ${it.trim()}") }
    coroutineContext.cancelChildren()
}
