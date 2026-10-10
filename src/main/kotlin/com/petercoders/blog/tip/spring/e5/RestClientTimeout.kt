package com.petercoders.blog.tip.spring.e5

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors

// tip-p15 (Spring 쪽) — RestClient + JdkClientHttpRequestFactory(readTimeout = 1s) 를 withTimeout(100) 아래에서 부르면 언제 끝나나.
// HttpTimeout.java 와 같은 서버: 헤더는 바로, 바디는 200ms 마다 1바이트씩 3초(15바이트).
//   A: readTimeout 1s 만(withTimeout 없음)
//   B: withTimeout(100) { withContext(IO) { restClient } }
//   C: withTimeout(100) { runInterruptible(IO) { restClient } }
fun run() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/slow") { ex ->
        ex.sendResponseHeaders(200, 0)
        runCatching { ex.responseBody.use { os -> repeat(15) { os.write('x'.code); os.flush(); Thread.sleep(200) } } }
    }
    server.executor = Executors.newCachedThreadPool()
    server.start()
    val uri = "http://127.0.0.1:${server.address.port}/slow"
    val client = RestClient.builder().requestFactory(JdkClientHttpRequestFactory().apply { setReadTimeout(Duration.ofSeconds(1)) }).build()
    fun call(): String = client.get().uri(uri).retrieve().body(String::class.java) ?: "(null)"
    fun describe(r: Result<String>) = r.fold({ "returned body length=${it.length}" }, { e ->
        "${e.javaClass.name}: ${e.message?.take(90)}" + (e.cause?.let { " | cause=${it.javaClass.name}: ${it.message?.take(60)}" } ?: "") })
    fun case(label: String, block: () -> String) {
        val t0 = System.nanoTime()
        val r = runCatching(block)
        println("%-62s %5d ms  %s".format(label, (System.nanoTime() - t0) / 1_000_000, describe(r)))
    }
    runCatching { call() }   // 워밍업
    for (rep in 1..3) {
        println("=== run $rep ===")
        case("A RestClient readTimeout=1s (no withTimeout)") { call() }
        case("B withTimeout(100){withContext(IO){RestClient readTimeout=1s}}") { runBlocking { withTimeout(100) { withContext(Dispatchers.IO) { call() } } } }
        case("C withTimeout(100){runInterruptible(IO){RestClient readTimeout=1s}}") { runBlocking { withTimeout(100) { runInterruptible(Dispatchers.IO) { call() } } } }
    }
    server.stop(0)
}
