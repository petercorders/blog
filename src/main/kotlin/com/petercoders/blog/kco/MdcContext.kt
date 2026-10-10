import kotlinx.coroutines.*
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.MDC

// kco-e16 — MDCContext 는 만들 때의 MDC 를 캡처한다. Coroutine 안에서 MDC.put 한 값은 다음 suspension 에서 사라진다.
fun main() = runBlocking {
    // BasicMDCAdapter(slf4j-simple)는 InheritableThreadLocal 이라, MDC 가 든 스레드가 worker 를 만들면 값이 상속된다.
    // 그 오염을 막으려고 Default worker 를 먼저 전부 띄워 둔다.
    (1..64).map { launch(Dispatchers.Default) { Thread.sleep(20) } }.joinAll()
    println("MDC adapter = ${MDC.getMDCAdapter().javaClass.name}")
    repeat(3) { r ->
        println("=== run ${r + 1} ===")
        val a = withContext(Dispatchers.Default + MDCContext()) {
            MDC.put("traceId", "put-inside"); val before = MDC.get("traceId"); delay(10); before to MDC.get("traceId")
        }
        println("(a) MDC.put inside coroutine (MDCContext captured empty MDC): before delay=${a.first}  after delay=${a.second}")

        MDC.put("traceId", "req-1"); val ctxAfterPut = MDCContext(); MDC.remove("traceId")
        val b = withContext(Dispatchers.Default + ctxAfterPut) { delay(10); MDC.get("traceId") }
        println("(b) MDCContext() created AFTER  MDC.put(req-1): in coroutine after delay=$b")

        val ctxBeforePut = MDCContext(); MDC.put("traceId", "req-2")
        val c = withContext(Dispatchers.Default + ctxBeforePut) { delay(10); MDC.get("traceId") }
        MDC.remove("traceId")
        println("(c) MDCContext() created BEFORE MDC.put(req-2): in coroutine after delay=$c")

        val plain = (1..64).map { i -> async(Dispatchers.Default) {
            MDC.put("traceId", "t$i"); delay(10); val got = MDC.get("traceId"); if (got == "t$i") null else "t$i->$got"
        } }.awaitAll().filterNotNull()
        println("(d) no MDCContext, MDC.put -> delay(10) -> MDC.get x64: mismatches=${plain.size}/64  sample=${plain.take(3)}")

        val wrapped = (1..64).map { i ->
            MDC.put("traceId", "w$i"); val ctx = MDCContext(); MDC.remove("traceId")
            async(Dispatchers.Default + ctx) { delay(10); val got = MDC.get("traceId"); if (got == "w$i") null else "w$i->$got" }
        }.awaitAll().filterNotNull()
        println("(e) MDC.put then MDCContext() per coroutine x64: mismatches=${wrapped.size}/64")
    }
}
