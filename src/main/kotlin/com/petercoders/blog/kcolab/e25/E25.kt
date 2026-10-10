package com.petercoders.blog.kcolab.e25

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

// kco-e25 — @Cacheable·@Async 를 suspend fun 에 붙이면 Spring 7.0.9 에서 어떻게 되나
@SpringBootApplication
@EnableCaching
@EnableAsync
class E25App {
    @Bean fun cacheManager(): CacheManager = ConcurrentMapCacheManager("items")
}

@Service
class CacheSvc {
    val calls = AtomicInteger()
    @Cacheable("items")
    suspend fun item(id: Long): String { calls.incrementAndGet(); delay(10); return "item-$id" }
}

@Service
class AsyncSvc {
    @Async
    suspend fun work(): String { delay(10); return "done on ${Thread.currentThread().name}" }
}

fun run() {
    val ctx = SpringApplicationBuilder(E25App::class.java).web(WebApplicationType.NONE)
        .properties("spring.main.banner-mode=off", "logging.level.root=WARN").run()
    val cache = ctx.getBean(CacheSvc::class.java); val async = ctx.getBean(AsyncSvc::class.java)
    println("CacheSvc bean = ${cache.javaClass.name} | AsyncSvc bean = ${async.javaClass.name}")
    val nativeCache = { (ctx.getBean(CacheManager::class.java).getCache("items")?.nativeCache as Map<*, *>?)?.mapValues { "${it.value} (${it.value?.javaClass?.name})" } }
    runBlocking {
        // 각 호출을 runBlocking 과 끊긴 별도 Coroutine 에 올리고, 취소 가능한 join 에 3초 타임아웃을 건다(재개되지 않는 호출이 있어도 러너가 멈추지 않게).
        suspend fun call(label: String, block: suspend () -> String) {
            var result: String? = null
            val job = CoroutineScope(Dispatchers.Default).launch { result = runCatching { block() }.getOrElse { "threw ${it.javaClass.name}: ${it.message}" } }
            if (withTimeoutOrNull(3000) { job.join() } == null) { println("$label: 3초 안에 재개되지 않음(호출한 Coroutine 이 멈춘 채 남음)"); job.cancel() }
            else println("$label: $result")
        }
        call("@Cacheable suspend fun item(1) call 1") { cache.item(1) }
        println("  bodyCalls=${cache.calls.get()}  cache 'items' after call 1 = ${nativeCache()}")
        call("@Cacheable suspend fun item(1) call 2") { cache.item(1) }
        println("  bodyCalls=${cache.calls.get()}  cache 'items' after call 2 = ${nativeCache()}")
        call("@Async suspend fun work()") { async.work() }
    }
    Thread.sleep(300)
    ctx.close()
}
