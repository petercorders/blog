// SPRING-INTERNALS 5편(@Async·@Scheduled) — 2차 실험(A1d~A6)의 프로브 빈과 프로파일별 설정.
// 프로파일마다 Bean 구성이 달라지므로, 기존 실험(A1~A4b)에 영향이 없도록 전부 @Profile 아래에 둔다.
package com.petercoders.blog.async

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.ThreadPoolExecutor
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.core.task.TaskDecorator
import org.springframework.resilience.annotation.ConcurrencyLimit
import org.springframework.resilience.annotation.EnableResilientMethods
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * @EnableScheduling 을 앱 클래스에서 떼어 프로파일로 끌 수 있게 했다.
 * A1e (ii)·(iv) 는 'nosched' 로 @EnableScheduling 없는 앱을 만든다. 나머지 실험은 전과 같이 켜져 있다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!nosched")
@EnableScheduling
class SchedulingOn

// ------------------------------------------------------------------ A1d-5: 거절 정책은 Customizer 로

@Configuration(proxyBeanMethods = false)
@Profile("callerruns")
class CallerRunsConfig {
    @Bean
    fun callerRuns() = ThreadPoolTaskExecutorCustomizer { it.setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy()) }
}

// ------------------------------------------------------------------ A1e: Executor Bean 매트릭스

/** (iii)(iv) ThreadPoolTaskExecutor Bean 'myPool'. 이름이 taskExecutor 가 아니고 @Primary 도 없다(카카오페이 예시와 같은 조건). */
@Configuration(proxyBeanMethods = false)
@Profile("e-pool")
class PoolExecutorConfig {
    @Bean
    fun myPool(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2; setThreadNamePrefix("my-")
    }
}

/** (v) 같은 종류의 Bean 을 이름 'taskExecutor' 로. */
@Configuration(proxyBeanMethods = false)
@Profile("e-te")
class NamedTaskExecutorConfig {
    @Bean
    fun taskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2; setThreadNamePrefix("te-")
    }
}

/** (vi) Boot 문서가 권하는 탈출구 — defaultCandidate=false + Boot builder. */
@Configuration(proxyBeanMethods = false)
@Profile("e-report")
class ReportExecutorConfig {
    @Bean(defaultCandidate = false)
    fun reportExecutor(builder: ThreadPoolTaskExecutorBuilder): ThreadPoolTaskExecutor =
        builder.threadNamePrefix("report-").build()
}

/** (viii) ThreadPoolTaskExecutor @Primary. */
@Configuration(proxyBeanMethods = false)
@Profile("e-primary")
class PrimaryExecutorConfig {
    @Bean
    @Primary
    fun myPool(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2; setThreadNamePrefix("my-")
    }
}

// ------------------------------------------------------------------ A2e: 컨텍스트 전파

/** 호출 스레드와 @Async 스레드에서 같은 네 가지를 읽는다. */
fun ctxSnapshot(): String {
    val t = Thread.currentThread()
    return "thread=${t.name} MDC.traceId=${MDC.get("traceId")} " +
        "auth=${SecurityContextHolder.getContext().authentication?.name} " +
        "txActive=${TransactionSynchronizationManager.isActualTransactionActive()}"
}

@Component
class CtxProbe {
    @Async
    fun snapDefault(): CompletableFuture<String> = CompletableFuture.completedFuture(ctxSnapshot())

    @Async("plainPool")
    fun snapPlain(): CompletableFuture<String> = CompletableFuture.completedFuture(ctxSnapshot())

    @Async("builderPool")
    fun snapBuilder(): CompletableFuture<String> = CompletableFuture.completedFuture(ctxSnapshot())
}

/** new 로 만든 실행자와 Boot builder 로 만든 실행자. 둘 다 defaultCandidate=false 라 applicationTaskExecutor 는 남는다. */
@Configuration(proxyBeanMethods = false)
@Profile("pools")
class PoolsConfig {
    @Bean(defaultCandidate = false)
    fun plainPool(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 8; setThreadNamePrefix("plain-")
    }

    @Bean(defaultCandidate = false)
    fun builderPool(builder: ThreadPoolTaskExecutorBuilder): ThreadPoolTaskExecutor =
        builder.threadNamePrefix("builder-").build()
}

/** MDC 를 복사하는 TaskDecorator Bean. */
@Configuration(proxyBeanMethods = false)
@Profile("mdc")
class MdcDecoratorConfig {
    @Bean
    fun mdcTaskDecorator(): TaskDecorator = TaskDecorator { task ->
        val captured = MDC.getCopyOfContextMap()
        Runnable {
            val previous = MDC.getCopyOfContextMap()
            if (captured != null) MDC.setContextMap(captured) else MDC.clear()
            try { task.run() } finally { if (previous != null) MDC.setContextMap(previous) else MDC.clear() }
        }
    }
}

// ------------------------------------------------------------------ A2f: 같은 풀 안에서 결과를 기다리기

@Configuration(proxyBeanMethods = false)
@Profile("nested")
class NestedConfig {
    @Bean(defaultCandidate = false)
    fun innerExecutor(builder: ThreadPoolTaskExecutorBuilder): ThreadPoolTaskExecutor =
        builder.corePoolSize(2).threadNamePrefix("inner-").build()
}

@Component
@Profile("nested")
class NestedInner {
    @Async
    fun inner(): CompletableFuture<String> = CompletableFuture.completedFuture(Thread.currentThread().name)

    @Async("innerExecutor")
    fun innerSeparate(): CompletableFuture<String> = CompletableFuture.completedFuture(Thread.currentThread().name)
}

@Component
@Profile("nested")
class NestedOuter(private val inner: NestedInner) {
    @Async
    fun outer(separate: Boolean): CompletableFuture<String> {
        val me = Thread.currentThread().name
        val t0 = System.currentTimeMillis()
        val f = if (separate) inner.innerSeparate() else inner.inner()
        return try {
            val got = f.get(3, TimeUnit.SECONDS)
            CompletableFuture.completedFuture("outer@$me inner 결과=$got (${System.currentTimeMillis() - t0}ms)")
        } catch (e: TimeoutException) {
            CompletableFuture.completedFuture("outer@$me ${e.javaClass.name} (${System.currentTimeMillis() - t0}ms)")
        }
    }
}

// ------------------------------------------------------------------ A2g: Transaction 과 @Async, AFTER_COMMIT

data class OrderPlaced(val tag: String)

object A2gLog {
    val lines = ConcurrentLinkedQueue<String>()
    val sideEffects = AtomicInteger()
}

@Component
@Profile("txev")
class TxEvProbe {
    /** 200ms 뒤 별도 커넥션으로 orders 행 수를 센다. */
    @Async
    fun countRowsLater(): CompletableFuture<String> {
        Thread.sleep(200)
        return CompletableFuture.completedFuture("rows=${Db.countOrders()} thread=${Thread.currentThread().name}")
    }

    /** 롤백 시나리오의 부수효과. 돌았는지만 센다. */
    @Async
    fun sideEffect(tag: String) {
        A2gLog.sideEffects.incrementAndGet()
        A2gLog.lines += "@Async sideEffect($tag) 실행 thread=${Thread.currentThread().name}"
    }
}

@Component
@Profile("txev")
class OrderListeners {
    /** 기본 phase = AFTER_COMMIT. 커밋 뒤 다른 스레드로 넘긴다. */
    @Async
    @TransactionalEventListener
    fun afterCommit(e: OrderPlaced) {
        A2gLog.lines += "AFTER_COMMIT @Async 리스너 tag=${e.tag} rows=${Db.countOrders()} thread=${Thread.currentThread().name}"
    }

    /** Transaction 이 없을 때도 실행되는 리스너. */
    @TransactionalEventListener(fallbackExecution = true)
    fun fallback(e: OrderPlaced) {
        A2gLog.lines += "fallbackExecution=true 리스너 tag=${e.tag} thread=${Thread.currentThread().name}"
    }
}

// ------------------------------------------------------------------ A3d: @Scheduled 예외 뒤 다음 주기

object FlakyRuns { val n = AtomicInteger() }

@Component
@Profile("err")
class FlakyJob {
    @Scheduled(fixedRate = 1000)
    fun flaky() {
        val n = FlakyRuns.n.incrementAndGet()
        SchedLog.mark("flaky#$n", "start")
        if (n % 2 == 1) {
            SchedLog.mark("flaky#$n", "throw")
            throw Boom("flaky run $n")
        }
        SchedLog.mark("flaky#$n", "end")
    }
}

// ------------------------------------------------------------------ A3e: 겹침과 제한 수단

private fun body(tag: String, ms: Long) {
    SchedLog.mark(tag, "start")
    try { Thread.sleep(ms); SchedLog.mark(tag, "end") } catch (e: InterruptedException) { SchedLog.mark(tag, "intr") }
}

/** (0)(a) 제한 없음. (a) 는 같은 Bean 에 spring.task.scheduling.simple.concurrency-limit=1 을 준다. */
@Component
@Profile("ovl0")
class OverlapPlain {
    @Scheduled(fixedRate = 2000) fun delta() = body("delta", 3000)
    @Scheduled(cron = "*/2 * * * * *") fun epsilon() = body("epsil", 3000)
    @Scheduled(fixedRate = 2000) fun zeta() = body("zeta", 100)
}

/** (b) @ConcurrencyLimit(1) — 기본 policy BLOCK. */
@Component
@Profile("ovlb")
class OverlapLimited {
    @ConcurrencyLimit(1) @Scheduled(fixedRate = 2000) fun delta() = body("delta", 3000)
    @ConcurrencyLimit(1) @Scheduled(cron = "*/2 * * * * *") fun epsilon() = body("epsil", 3000)
    @Scheduled(fixedRate = 2000) fun zeta() = body("zeta", 100)
}

/** (c) @ConcurrencyLimit(limit = 1, policy = REJECT). */
@Component
@Profile("ovlc")
class OverlapReject {
    @ConcurrencyLimit(limit = 1, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
    @Scheduled(fixedRate = 2000) fun delta() = body("delta", 3000)
    @ConcurrencyLimit(limit = 1, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
    @Scheduled(cron = "*/2 * * * * *") fun epsilon() = body("epsil", 3000)
    @Scheduled(fixedRate = 2000) fun zeta() = body("zeta", 100)
}

@Configuration(proxyBeanMethods = false)
@Profile("ovlb | ovlc")
@EnableResilientMethods
class ResilientOn

/** (d) Virtual Thread off. @Scheduled 메서드에 @Async 를 같이 붙인다. */
@Component
@Profile("ovld")
class OverlapAsync {
    @Async @Scheduled(fixedRate = 2000) fun delta() = body("delta", 3000)
}

// ------------------------------------------------------------------ A4c (b): 실행 중인 @Scheduled 잡의 종료

@Component
@Profile("sched4")
class Sched4 {
    @Scheduled(fixedRate = 10000)
    fun four() {
        Counters.started.incrementAndGet()
        SchedLog.mark("four", "start")
        try {
            Thread.sleep(4000)
            Counters.finished.incrementAndGet()
            Db.insert("sched4")
            SchedLog.mark("four", "end")
        } catch (e: InterruptedException) {
            Counters.interrupted.incrementAndGet()
            SchedLog.mark("four", "intr")
            Thread.currentThread().interrupt()
        }
    }
}

// ------------------------------------------------------------------ A4f: 실제 SIGTERM 경로(web)

@Component
@Profile("web")
class WebJob {
    /** 끝까지 돌면 마커 파일을 쓴다. 경로는 -Da4f.marker 로 받는다. */
    @Async
    fun markerJob(seconds: Long) {
        println("[job] start thread=${Thread.currentThread().name} virtual=${Thread.currentThread().isVirtual}")
        try {
            Thread.sleep(seconds * 1000)
            File(System.getProperty("a4f.marker")).writeText("done")
            println("[job] done -> marker written")
        } catch (e: InterruptedException) {
            println("[job] interrupted")
            Thread.currentThread().interrupt()
        }
    }
}

@RestController
@Profile("web")
class SlowController(private val job: WebJob) {
    /** 8초 @Async 를 넘기고, 요청 자체는 5초 걸린다. */
    @GetMapping("/slow")
    fun slow(): String {
        job.markerJob(8)
        return try {
            Thread.sleep(5000); println("[slow] done"); "slow done"
        } catch (e: InterruptedException) {
            println("[slow] interrupted"); Thread.currentThread().interrupt(); "slow interrupted"
        }
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("web")
class WebSecurityOpen {
    @Bean
    fun permitAll(
        http: org.springframework.security.config.annotation.web.builders.HttpSecurity,
    ): org.springframework.security.web.SecurityFilterChain =
        http.csrf { it.disable() }.authorizeHttpRequests { it.anyRequest().permitAll() }.build()
}

// ------------------------------------------------------------------ A5: 자기 호출과 자기 주입

/** allow-circular-references=true 에서 필드 자기 주입. @Async Proxy 는 AsyncAnnotationBeanPostProcessor 가 만든다. */
@Component
@Profile("selfinj")
class SelfInjAsync {
    @Autowired lateinit var self: SelfInjAsync

    @Async
    fun hello(): CompletableFuture<String> = CompletableFuture.completedFuture(Thread.currentThread().name)
}

/** @Lazy 자기 주입 — 전역 플래그 없이. */
@Component
@Profile("selflazy")
class SelfLazyAsync {
    @Lazy @Autowired lateinit var self: SelfLazyAsync

    @Async
    fun hello(): CompletableFuture<String> = CompletableFuture.completedFuture(Thread.currentThread().name)

    fun viaThis(): String = hello().get(10, TimeUnit.SECONDS)
    fun viaSelf(): String = self.hello().get(10, TimeUnit.SECONDS)
}
