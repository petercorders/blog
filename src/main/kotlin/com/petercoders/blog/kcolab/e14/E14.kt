package com.petercoders.blog.kcolab.e14

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.coroutines.ContinuationInterceptor

// kco-e14 — Spring MVC 의 suspend 핸들러는 어느 스레드에서 시작하고, 첫 suspension 뒤 어디서 재개되나.
@SpringBootApplication
class E14App

@RestController
class ProbeController {
    @Volatile lateinit var client: WebClient

    @GetMapping("/hello") fun hello() = "hello"

    @GetMapping("/probe")
    suspend fun probe(): String {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]
        val entry = Thread.currentThread().name
        delay(10)
        val afterDelay = Thread.currentThread().name
        client.get().uri("/hello").retrieve().awaitBody<String>()
        val afterWebClient = Thread.currentThread().name
        val inIo = withContext(Dispatchers.IO) { Thread.currentThread().name }
        val afterWithContext = Thread.currentThread().name
        return "dispatcher=$dispatcher | entry=$entry | after delay(10)=$afterDelay | after WebClient awaitBody()=$afterWebClient" +
            " | inside withContext(IO)=$inIo | after withContext(IO) returns=$afterWithContext"
    }

    @GetMapping("/block")
    suspend fun block(): String { delay(1); Thread.sleep(200); return Thread.currentThread().name }

    @GetMapping("/block-io")
    suspend fun blockIo(): String { delay(1); return withContext(Dispatchers.IO) { Thread.sleep(200); Thread.currentThread().name } }

    @GetMapping("/plain")
    suspend fun plain(): String { delay(10); return "plain ok on ${Thread.currentThread().name}" }
}

private fun start() = SpringApplicationBuilder(E14App::class.java)
    .properties("server.port=0", "spring.main.banner-mode=off", "logging.level.root=WARN")
    .run()

private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
private fun get(port: String, path: String) =
    http.sendAsync(HttpRequest.newBuilder(URI("http://localhost:$port$path")).build(), HttpResponse.BodyHandlers.ofString())

fun run() {
    val ctx = start()
    val port = ctx.environment.getProperty("local.server.port")!!
    ctx.getBean(ProbeController::class.java).client = WebClient.create("http://localhost:$port")
    println("server.port=0 -> local.server.port=$port")
    repeat(3) { println("GET /probe #${it + 1}: ${get(port, "/probe").join().body()}") }
    get(port, "/block").join(); get(port, "/block-io").join()   // 워밍업 1회씩
    for (path in listOf("/block", "/block-io")) repeat(3) { r ->
        val t0 = System.nanoTime()
        val bodies = (1..20).map { get(port, path) }.map { it.join() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val threads = bodies.groupingBy { it.body() }.eachCount()
        println("20 concurrent GET $path (run ${r + 1}): total=$ms ms  status=${bodies.map { it.statusCode() }.toSet()}  sleptOn=$threads")
    }
    ctx.close()
}

// kotlinx-coroutines-reactor 를 런타임에서 뺀 채(kcoLabNoReactor) suspend 핸들러를 부르면 어떻게 되나
fun runNoReactorCheck() {
    val ctx = start()
    val port = ctx.environment.getProperty("local.server.port")!!
    val r = get(port, "/plain").join()
    println("GET /plain without kotlinx-coroutines-reactor: status=${r.statusCode()} body=${r.body().take(300)}")
    ctx.close()
}
