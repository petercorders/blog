// SPRING-INTERNALS 3편(예외 처리는 한 곳이 아니라 경계마다 다르다) — 프로브 정의.
// 러너는 ExcLab.kt. 마커 [X1]~[X9] 와 runs/xN.log 가 1:1 로 대응한다.
//
// 프로파일로 컨텍스트 구성을 갈라 쓴다.
//   (없음)     : advice 가 하나도 없는 맨 컨텍스트 — [X3] 기본 응답 바디 측정용
//   handlers   : AdviceA(@Order(1)) · AdviceB(@Order(2)) · 컨트롤러 로컬 핸들러 — [X2]
//   reeh       : ResponseEntityExceptionHandler 를 상속만 한 advice — [X4]
//   asynch     : AsyncUncaughtExceptionHandler 를 커스텀으로 갈아끼운 설정 — [X7b]
package com.petercoders.blog.exc

import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------- 서블릿 필터

/** 모든 디스패치(REQUEST/ERROR/ASYNC)를 기록한다. [X6] 의 증거는 이 목록이지 로그가 아니다. */
class TraceFilter : Filter {
    override fun doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
        val http = req as HttpServletRequest
        events += "${http.dispatcherType} ${http.requestURI}"
        chain.doFilter(req, res)
    }
    companion object { val events = CopyOnWriteArrayList<String>() }
}

/** [X5] DispatcherServlet 에 들어가기 전에 터지는 예외. */
class BoomFilter : Filter {
    override fun doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
        if ((req as HttpServletRequest).requestURI == "/x5/filter-boom") {
            throw IllegalStateException("filter failure")
        }
        chain.doFilter(req, res)
    }
}

@Configuration(proxyBeanMethods = false)
class ExcFilterConfig {
    @Bean
    fun traceFilter() = FilterRegistrationBean(TraceFilter()).apply {
        addUrlPatterns("/*")
        setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC)
        order = Int.MIN_VALUE + 10          // 가장 바깥
    }

    @Bean
    fun boomFilter() = FilterRegistrationBean(BoomFilter()).apply {
        addUrlPatterns("/*")
        order = Int.MIN_VALUE + 20          // TraceFilter 안쪽, 나머지 바깥
    }
}

// ---------------------------------------------------------------- 컨트롤러

@RestController
class ExcController(private val svc: ExcService, private val async: AsyncProbe) {

    @GetMapping("/x2/ise")
    fun ise(): Nothing = throw IllegalStateException("x2-ise")

    @GetMapping("/x2/iae")
    fun iae(): Nothing = throw IllegalArgumentException("x2-iae")

    @GetMapping("/x2/npe")
    fun npe(): Nothing = throw NullPointerException("x2-npe")

    /** [X4] 필수 파라미터 누락 → MissingServletRequestParameterException(ServletException 계열). */
    @GetMapping("/x4/param")
    fun param(@RequestParam q: String) = mapOf("q" to q)

    @GetMapping("/x7/async-void")
    fun asyncVoid(): Map<String, String> { async.boomVoid(); return mapOf("ok" to "void") }

    @GetMapping("/x7/async-future")
    fun asyncFuture(): Map<String, String> { async.boomFuture(); return mapOf("ok" to "future-not-joined") }

    @GetMapping("/x7/async-join")
    fun asyncJoin(): Map<String, String> { async.boomFuture().join(); return mapOf("ok" to "joined") }

    @GetMapping("/x8/before-commit")
    fun beforeCommit() = mapOf("tag" to svc.writeThenFail("beforeCommit"))

    @GetMapping("/x8/before-completion")
    fun beforeCompletion() = mapOf("tag" to svc.writeThenFail("beforeCompletion"))

    @GetMapping("/x8/after-commit")
    fun afterCommit() = mapOf("tag" to svc.writeThenFail("afterCommit"))

    @GetMapping("/x8/after-completion")
    fun afterCompletion() = mapOf("tag" to svc.writeThenFail("afterCompletion"))

    @GetMapping("/x8/plain")
    fun plain() = mapOf("tag" to svc.writeThenThrow("plain"))

    @GetMapping("/rows")
    fun rows(@RequestParam tag: String) = mapOf("rows" to svc.rows(tag))
}

/** 컨트롤러 로컬 `@ExceptionHandler` 가 advice 보다 먼저 보이는지 재는 전용 컨트롤러. */
@Profile("handlers")
@RestController
class LocalHandlerController {
    @GetMapping("/x2/local-ise")
    fun boom(): Nothing = throw IllegalStateException("x2-local-ise")

    @ExceptionHandler(IllegalStateException::class)
    fun local(e: IllegalStateException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(521).body(mapOf("who" to "controller-local#ISE", "ex" to e.javaClass.name))
}

// ---------------------------------------------------------------- advice

/** 상속 거리가 먼 핸들러 둘을 한 클래스에 둔다. 클래스 안 승자는 ExceptionDepthComparator 가 고른다. */
@Profile("handlers")
@Order(1)
@RestControllerAdvice
class AdviceA {
    @ExceptionHandler(RuntimeException::class)
    fun rte(e: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(541).body(mapOf("who" to "AdviceA#RuntimeException", "ex" to e.javaClass.name))

    @ExceptionHandler(IllegalArgumentException::class)
    fun iae(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(542).body(mapOf("who" to "AdviceA#IllegalArgumentException", "ex" to e.javaClass.name))
}

/** AdviceA 보다 order 가 크고, 예외 클래스는 더 구체적이다. 둘 중 무엇이 이기는지가 [X2] 의 질문이다. */
@Profile("handlers")
@Order(2)
@RestControllerAdvice
class AdviceB {
    @ExceptionHandler(IllegalStateException::class)
    fun ise(e: IllegalStateException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(561).body(mapOf("who" to "AdviceB#IllegalStateException", "ex" to e.javaClass.name))
}

/** [X4] 상속만 하고 아무것도 오버라이드하지 않는다. 무엇이 자동으로 덮이는지를 응답으로 본다. */
@Profile("reeh")
@RestControllerAdvice
class InheritOnlyAdvice :
    org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler()

// ---------------------------------------------------------------- 비동기

@Service
class AsyncProbe {
    @Async
    fun boomVoid() { throw IllegalStateException("async-void failure") }

    @Async
    fun boomFuture(): CompletableFuture<String> = throw IllegalStateException("async-future failure")
}

/** [X7b] 기본 SimpleAsyncUncaughtExceptionHandler 를 카운터로 갈아끼운 설정. */
@Profile("asynch")
@Configuration(proxyBeanMethods = false)
class CountingAsyncConfig : AsyncConfigurer {
    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler =
        AsyncUncaughtExceptionHandler { ex: Throwable, _: Method, _: Array<out Any?> ->
            caught.incrementAndGet()
            last = ex.javaClass.name
        }
    companion object {
        val caught = AtomicInteger()
        @Volatile var last: String? = null
    }
}

// ---------------------------------------------------------------- 트랜잭션

@Service
class ExcService(private val jdbc: JdbcTemplate) {

    @Transactional
    fun writeThenFail(phase: String): String {
        jdbc.update("insert into exc_log(tag) values (?)", phase)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun beforeCommit(readOnly: Boolean) {
                if (phase == "beforeCommit") throw IllegalStateException("sync failure at beforeCommit")
            }
            override fun beforeCompletion() {
                if (phase == "beforeCompletion") throw IllegalStateException("sync failure at beforeCompletion")
            }
            override fun afterCommit() {
                if (phase == "afterCommit") throw IllegalStateException("sync failure at afterCommit")
            }
            override fun afterCompletion(status: Int) {
                if (phase == "afterCompletion") throw IllegalStateException("sync failure at afterCompletion")
            }
        })
        return phase
    }

    /** 대조군 — 메서드 본문에서 바로 던진다. */
    @Transactional
    fun writeThenThrow(tag: String): String {
        jdbc.update("insert into exc_log(tag) values (?)", tag)
        throw IllegalStateException("service failure")
    }

    fun rows(tag: String): Int =
        jdbc.queryForObject("select count(*) from exc_log where tag = ?", Int::class.java, tag) ?: -1
}

// ---------------------------------------------------------------- 시큐리티

/**
 * 저장소에 spring-boot-starter-security 가 이미 들어 있다(2편이 넣었다).
 * 이 글의 실험은 인가가 아니라 예외 경계를 재므로 전부 permitAll 로 열어 둔다.
 * 시큐리티 필터가 체인에 그대로 남는다는 사실 자체는 [X1] 에서 순서로 찍는다.
 */
@Configuration(proxyBeanMethods = false)
class ExcSecurityConfig {
    @Bean
    fun permitEverything(
        http: org.springframework.security.config.annotation.web.builders.HttpSecurity,
    ): org.springframework.security.web.SecurityFilterChain =
        http.csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
}

/** HttpStatus 를 문자열로 붙일 때만 쓰는 헬퍼. */
fun statusText(code: Int): String = runCatching { HttpStatus.valueOf(code).name }.getOrDefault("-")
