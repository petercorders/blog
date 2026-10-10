package com.petercoders.blog.kcolab.snippets

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

// 2편 본문 요지 코드 컴파일 확인용(실행하지 않는다). 어느 실험 앱의 component scan 범위에도 들지 않는 패키지다.

// ① L3: 블로킹 호출 바로 바깥에서 주입받은 Dispatcher 로 감싼다
class OrderDao(private val jdbc: JdbcTemplate, private val db: CoroutineDispatcher) {
    suspend fun count(): Int = withContext(db) { jdbc.queryForObject("select count(*) from orders", Int::class.java)!! }
}

// ③ L3: DB view 는 커넥션 풀 크기와 같은 값에서 뽑는다
@Configuration
class DispatcherConfig {
    @Bean
    fun dbDispatcher(@Value("\${spring.datasource.hikari.maximum-pool-size:10}") n: Int): CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(n, "db")
}

// ③ L3: suspend 호출에는 kotlinx Semaphore 로 상한을 건다
data class Item(val id: Long)
class ItemClient(private val webClient: WebClient) {
    private val external = Semaphore(20)
    suspend fun call(id: Long) = external.withPermit { webClient.get().uri("/items/{id}", id).retrieve().awaitBody<Item>() }
}

// ⑤ L3: Bean 은 scope 를 필드 하나로 들고 @PreDestroy 에서 닫는다
class ExcelJobs(dbDispatcher: CoroutineDispatcher) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + dbDispatcher + CoroutineName("excel") +
        CoroutineExceptionHandler { _, e -> log.error("excel job failed", e) })
    fun submit(block: suspend () -> Unit) = scope.launch { block() }
    @PreDestroy fun close() = scope.cancel()
}
