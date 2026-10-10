import kotlinx.coroutines.*

// kco-e19 — (a) CoroutineExceptionHandler 는 어디서 불리나  (b) launch(SupervisorJob()) 은 scope.cancel() 을 피해 가나
fun handler(tag: String) = CoroutineExceptionHandler { _, e -> println("  CEH[$tag] called: ${e.message}") }

fun main() = runBlocking {
    Thread.setDefaultUncaughtExceptionHandler { t, e -> println("  uncaught on ${t.name}: ${e.javaClass.simpleName} ${e.message}") }

    println("(a1) root launch(CEH[root]) { launch { throw } }")
    CoroutineScope(Job()).launch(handler("root")) { launch { throw IllegalStateException("child of root") } }.join()

    println("(a2) root launch { launch(CEH[child]) { throw } }")
    CoroutineScope(Job()).launch { launch(handler("child")) { throw IllegalStateException("child with its own CEH") } }.join()

    println("(a3) supervisorScope { launch(CEH[sv-child]) { throw } }")
    supervisorScope { launch(handler("sv-child")) { throw IllegalStateException("direct child of supervisorScope") } }

    println("(a4) root async(CEH[async]) { throw }  then await()")
    val d = CoroutineScope(Job()).async(handler("async")) { throw IllegalStateException("root async") }
    try { d.await() } catch (e: IllegalStateException) { println("  await() threw: ${e.message}") }

    println("(b) scope.launch(SupervisorJob()) survives scope.cancel()")
    val scope = CoroutineScope(Job() + Dispatchers.Default)
    val tied = scope.launch { delay(Long.MAX_VALUE) }
    val detached = scope.launch(SupervisorJob()) { delay(Long.MAX_VALUE) }
    scope.cancel(); delay(100)
    println("  after scope.cancel() + 100 ms: tied.isActive=${tied.isActive}  detached(SupervisorJob).isActive=${detached.isActive}")
    detached.cancel()
}
