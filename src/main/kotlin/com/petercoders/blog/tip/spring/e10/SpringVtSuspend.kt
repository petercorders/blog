package com.petercoders.blog.tip.spring.e10

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor

// tip-p20 — spring.threads.virtual.enabled 가 true 여도 MVC suspend 핸들러는 첫 suspension 뒤 Virtual Thread 를 떠나는가.
// 진입 / delay(10) 뒤 / withContext(IO) 안·반환 뒤 / withContext(VT Dispatcher) 안·반환 뒤의 스레드와 필터가 넣은 MDC 값.
// 타이머: /block(delay(10) 뒤 Thread.sleep(2000)) 을 보낸 100ms 뒤 /timer(withTimeout(100){ delay(1000) }) 의 타임아웃이 몇 ms 에 발화하나.
@SpringBootApplication
class E10App

@Component
class MdcFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        MDC.put("reqId", request.getHeader("X-Req") ?: "none")
        try { chain.doFilter(request, response) } finally { MDC.remove("reqId") }
    }
}

private fun here(): String = Thread.currentThread().let { "${it.name.ifEmpty { "(unnamed VT #${it.threadId()})" }}[virtual=${it.isVirtual}, mdc=${MDC.get("reqId")}]" }

@RestController
class ProbeController {
    private val vt = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

    @GetMapping("/probe")
    suspend fun probe(): String {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]
        val entry = here()
        delay(10)
        val afterDelay = here()
        val inIo = withContext(Dispatchers.IO) { here() }
        val afterIo = here()
        val inVt = withContext(vt) { here() }
        val afterVt = here()
        return "dispatcher=$dispatcher\n    entry=$entry\n    after delay(10)=$afterDelay\n    inside withContext(IO)=$inIo\n    after withContext(IO) returns=$afterIo" +
            "\n    inside withContext(vtDispatcher)=$inVt\n    after withContext(vtDispatcher) returns=$afterVt"
    }

    @GetMapping("/block")
    suspend fun block(): String { delay(10); val t = here(); Thread.sleep(2000); return "slept 2000 ms on $t" }

    @GetMapping("/timer")
    suspend fun timer(): String {
        val t0 = System.nanoTime()
        val e = runCatching { withTimeout(100) { delay(1000) } }.exceptionOrNull()
        return "withTimeout(100){delay(1000)} ended at ${(System.nanoTime() - t0) / 1_000_000} ms with ${e?.javaClass?.simpleName} on ${here()}"
    }
}

private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
private fun get(port: String, path: String, req: String) =
    http.sendAsync(HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("X-Req", req).build(), HttpResponse.BodyHandlers.ofString())

fun run(vtEnabled: Boolean) {
    val ctx = SpringApplicationBuilder(E10App::class.java)
        .properties("server.port=0", "spring.main.banner-mode=off", "logging.level.root=WARN", "spring.threads.virtual.enabled=$vtEnabled")
        .run()
    val port = ctx.environment.getProperty("local.server.port")!!
    println("spring.threads.virtual.enabled=$vtEnabled  server.port=0 -> local.server.port=$port  spring.reactor.context-propagation=${ctx.environment.getProperty("spring.reactor.context-propagation") ?: "(unset)"}  Hooks.isAutomaticContextPropagationEnabled=${reactor.core.publisher.Hooks.isAutomaticContextPropagationEnabled()}")
    repeat(3) { println("GET /probe #${it + 1} (X-Req=r${it + 1}): ${get(port, "/probe", "r${it + 1}").join().body()}") }
    println("GET /timer alone (warm-up): ${get(port, "/timer", "t0").join().body()}")
    repeat(3) { r ->
        val alone = get(port, "/timer", "alone${r + 1}").join().body()
        val block = get(port, "/block", "b${r + 1}")
        Thread.sleep(100)
        val timer = get(port, "/timer", "t${r + 1}").join().body()
        println("run ${r + 1}: /timer alone -> $alone")
        println("run ${r + 1}: /timer 100 ms after /block -> $timer")
        println("run ${r + 1}: /block -> ${block.join().body()}")
    }
    ctx.close()
}
