// SPRING-INTERNALS 3편 — 2026-10 재편에서 더한 프로브.
// 러너는 ExcLabPlus.kt(X1+·X2b·X3+·X5b·X7+·X8b·X10~X14·X16·X11CTX·X7C), ExcLabX15.kt(X15), ExcLabObs.kt(X14OBS).
//
// 프로파일
//   scoped / scoped-only : [X2b] 범위를 좁힌 advice(ScopedAdvice·OrAdvice)와 좁히지 않은 UnscopedAdvice
//   catchall             : [X3+][X16] 별도 advice 의 Exception catch-all(@Order 없음)
//   catchall-highest     : [X3+] 같은 catch-all 을 @Order(HIGHEST_PRECEDENCE)로
//   reeh-catchall        : [X3+] ResponseEntityExceptionHandler 상속 클래스 안에 둔 catch-all
//   recorder             : [X10][X11][X12][X16] DispatcherServlet 이 실제로 넘긴 예외를 기록만 하는 Resolver
//   secured              : [X11] 인증·인가가 켜진 Security Filter Chain(httpBasic, user/USER)
//   sec-advice(-scoped)  : [X11] EntryPoint·AccessDeniedHandler 가 위임한 예외를 받는 advice
//   dup-filter           : [X11] @Component Filter 를 Security 체인에도 넣었을 때 호출 횟수
//   custom(-after)       : [X12] errorCode·traceId 를 ProblemDetail properties 로 내리는 advice(override 위치 두 가지)
//   error-attrs          : [X13] DefaultErrorAttributes 를 상속한 ErrorAttributes Bean
//   error-controller     : [X11][X13] /error 를 받는 ErrorController(ProblemDetail 반환)
//   traceid              : [X13] traceId Filter 네 변형(exc.trace.mode=i|ii|iii|iv)
//   methodsec            : [X16] @EnableMethodSecurity + @PreAuthorize Controller
//   catchall-rethrow     : [X16] catch-all 이 AccessDeniedException 을 그대로 되던진다
//   ctx-probe            : [X11CTX] Security Filter Chain 앞(-110)·뒤(-50)에서 dispatch 마다 SecurityContext 출처를 찍는 Filter
//   ise-only             : [X7C] IllegalStateException Handler 하나만 둔 advice(root 매치 없음 → cause 매치)
//   param-peek           : [X1UP] Security 보다 앞(order MIN+30)에서 업로드 요청의 getParameter 를 먼저 부르는 Filter
//   (exc.sec.token-mode=after) : [X11AFTER] 토큰 Filter 를 addFilterAfter(ExceptionTranslationFilter) 로 넣는다
//   idem                 : [X8IDEM] Idempotency-Key 의 첫 응답을 저장해 재생하는 Filter(order MIN+40, exc.idem.store-5xx=true|false)
package com.petercoders.blog.exc

import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.slf4j.MDC
import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.error.DefaultErrorAttributes
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.userdetails.User
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.ExceptionTranslationFilter
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalApplicationListener
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.validation.annotation.Validated
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import java.net.URI
import org.springframework.web.util.ContentCachingResponseWrapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private fun body(code: Int, who: String, e: Throwable): ResponseEntity<Map<String, String>> =
    ResponseEntity.status(code).body(mapOf("who" to who, "ex" to e.javaClass.name))

// ================================================================ [X2b] advice 범위
// 415 두 변형은 ExcController 와 같은 범위에 있어야 하므로 ExcController 에 엔드포인트를 둔다(ExcProbes.kt).

/** [X2b] OrAdvice 의 셀렉터 어느 쪽에도 해당하지 않는 Controller. */
@RestController
class X2bOtherController {
    @GetMapping("/x2b/other-ise")
    fun otherIse(): Nothing = throw IllegalStateException("x2b-other-ise")
}

@Profile("scoped | scoped-only")
@Order(10)
@RestControllerAdvice(assignableTypes = [ExcController::class])
class ScopedAdvice {
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun m405(e: Exception) = body(571, "ScopedAdvice#405", e)
    @ExceptionHandler(NoResourceFoundException::class)
    fun m404(e: Exception) = body(572, "ScopedAdvice#404", e)
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun m415(e: Exception) = body(573, "ScopedAdvice#415", e)
}

@Profile("scoped")
@Order(20)
@RestControllerAdvice
class UnscopedAdvice {
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun m405(e: Exception) = body(581, "UnscopedAdvice#405", e)
    @ExceptionHandler(NoResourceFoundException::class)
    fun m404(e: Exception) = body(582, "UnscopedAdvice#404", e)
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun m415(e: Exception) = body(583, "UnscopedAdvice#415", e)
}

/** basePackages 는 아무것도 가리키지 않고 assignableTypes 만 맞는다. OR 로 합쳐지면 ExcController 의 ISE 를 받는다. */
@Profile("scoped | scoped-only")
@Order(1)
@RestControllerAdvice(basePackages = ["no.such.pkg"], assignableTypes = [ExcController::class])
class OrAdvice {
    @ExceptionHandler(IllegalStateException::class)
    fun ise(e: IllegalStateException) = body(591, "OrAdvice#ISE", e)
}

// ================================================================ [X3+][X16] catch-all 배치

@Profile("catchall")
@RestControllerAdvice
class CatchAllAdvice {
    @ExceptionHandler(Exception::class)
    fun all(e: Exception) = body(599, "CatchAll#Exception", e)
}

@Profile("catchall-highest")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class CatchAllHighestAdvice {
    @ExceptionHandler(Exception::class)
    fun all(e: Exception) = body(599, "CatchAllHighest#Exception", e)
}

/** 상속 클래스 안의 catch-all. 표준 예외는 상속한 handleException 이 더 구체적이라 그쪽이 이긴다(같은 클래스 안 구체성). */
@Profile("reeh-catchall")
@RestControllerAdvice
class ReehWithCatchAllAdvice : ResponseEntityExceptionHandler() {
    @ExceptionHandler(Exception::class)
    fun all(e: Exception) = body(599, "ReehCatchAll#Exception", e)
}

/** [X16] catch-all 이지만 AccessDeniedException 은 원형 그대로 되던진다. */
@Profile("catchall-rethrow")
@RestControllerAdvice
class CatchAllRethrowAdvice {
    @ExceptionHandler(Exception::class)
    fun all(e: Exception): ResponseEntity<Map<String, String>> {
        if (e is AccessDeniedException) throw e
        return body(599, "CatchAllRethrow#Exception", e)
    }
}

// ================================================================ 기록용 Resolver

/** DispatcherServlet 이 Resolver 목록에 넘긴 예외(원인 사슬 포함)를 기록만 하고 null 을 돌려준다. 응답에는 손대지 않는다. */
@Profile("recorder")
@Component
class RecordingResolver : HandlerExceptionResolver, Ordered {
    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE
    override fun resolveException(
        request: HttpServletRequest, response: HttpServletResponse, handler: Any?, ex: Exception,
    ): ModelAndView? {
        seen += (if (withDispatch) "${request.dispatcherType} " else "") + causeChain(ex)
        return null
    }
    companion object {
        val seen = CopyOnWriteArrayList<String>()
        /** [X1UP] 만 켠다. 켜면 기록 앞에 dispatcher type 을 붙인다(다른 마커의 출력은 그대로). */
        @Volatile var withDispatch = false
        fun causeChain(t: Throwable): String {
            val names = mutableListOf<String>()
            var c: Throwable? = t
            while (c != null && names.size < 5) { names += c.javaClass.name; c = if (c.cause === c) null else c.cause }
            return names.joinToString(" <- cause ")
        }
    }
}

// ================================================================ [X7+] 기다리지 않은 Future 를 붙잡아 둔다

@RestController
class X7PlusController(private val async: AsyncProbe) {
    @GetMapping("/x7plus/future-kept")
    fun kept(): Map<String, String> {
        lastFuture = async.boomFuture()
        return mapOf("ok" to "future-not-joined")
    }
    companion object { @Volatile var lastFuture: CompletableFuture<String>? = null }
}

// ================================================================ [X8b][X8c] @TransactionalEventListener

data class ExcTxEvent(val phase: String)
data class ExcNoTxEvent(val tag: String)
data class ExcCallbackEvent(val tag: String)

@Service
class ExcEventService(private val jdbc: JdbcTemplate, private val publisher: ApplicationEventPublisher) {
    @Transactional
    fun publishThenFail(phase: String): String {
        jdbc.update("insert into exc_log(tag) values (?)", "listener-$phase")
        publisher.publishEvent(ExcTxEvent(phase))
        return phase
    }

    @Transactional
    fun publishCallback(): String {
        jdbc.update("insert into exc_log(tag) values (?)", "listener-callback")
        publisher.publishEvent(ExcCallbackEvent("callback"))
        return "callback"
    }

    /** [X8c] 트랜잭션 없이 publish 한다. */
    fun publishWithoutTx(): String {
        publisher.publishEvent(ExcNoTxEvent("no-tx"))
        return "published"
    }
}

@Component
class ExcTxListeners {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterCommit(e: ExcTxEvent) {
        if (e.phase == "AFTER_COMMIT") throw IllegalStateException("listener failure at AFTER_COMMIT")
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun beforeCommit(e: ExcTxEvent) {
        if (e.phase == "BEFORE_COMMIT") throw IllegalStateException("listener failure at BEFORE_COMMIT")
    }

    /** [X8e] 우아한형제들 7835 의 첫 코드 모양: @Async + BEFORE_COMMIT. 리스너가 다른 Thread 에서 돈다. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun asyncBeforeCommit(e: ExcTxEvent) {
        if (e.phase == "ASYNC_BEFORE_COMMIT") throw IllegalStateException("async listener failure at BEFORE_COMMIT")
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun noTxDefault(e: ExcNoTxEvent) { defaultCalls.incrementAndGet() }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun noTxFallback(e: ExcNoTxEvent) { fallbackCalls.incrementAndGet() }

    companion object {
        val defaultCalls = AtomicInteger()
        val fallbackCalls = AtomicInteger()
    }
}

/** [X8b] 리스너 실패를 알림으로 잇는 공식 훅(SynchronizationCallback.postProcessEvent)을 실제로 받아 본다. */
@Configuration(proxyBeanMethods = false)
class ExcCallbackListenerConfig {
    // forPayload 의 페이로드 타입은 Bean 정의에서 지워지므로(모든 PayloadApplicationEvent 를 받는다)
    // Any 로 받고 안에서 타입을 거른다. 콜백도 ExcCallbackEvent 일 때만 기록한다.
    @Bean
    fun callbackListener(): TransactionalApplicationListener<*> =
        TransactionalApplicationListener.forPayload<Any>(TransactionPhase.AFTER_COMMIT) { p ->
            if (p is ExcCallbackEvent) throw IllegalStateException("callback listener failure")
        }.also {
            it.addCallback(object : TransactionalApplicationListener.SynchronizationCallback {
                override fun postProcessEvent(event: ApplicationEvent, ex: Throwable?) {
                    if ((event as? PayloadApplicationEvent<*>)?.payload !is ExcCallbackEvent) return
                    callbackSeen += "postProcessEvent(payload=ExcCallbackEvent, ex=${ex?.javaClass?.name}: ${ex?.message})"
                }
            })
        }
    companion object { val callbackSeen = CopyOnWriteArrayList<String>() }
}

@RestController
class X8bController(private val events: ExcEventService) {
    @GetMapping("/x8/listener-after-commit")
    fun listenerAfterCommit() = mapOf("tag" to events.publishThenFail("AFTER_COMMIT"))

    @GetMapping("/x8/listener-before-commit")
    fun listenerBeforeCommit() = mapOf("tag" to events.publishThenFail("BEFORE_COMMIT"))

    @GetMapping("/x8/listener-async-before-commit")
    fun listenerAsyncBeforeCommit() = mapOf("tag" to events.publishThenFail("ASYNC_BEFORE_COMMIT"))

    @GetMapping("/x8/listener-callback")
    fun listenerCallback() = mapOf("tag" to events.publishCallback())

    @GetMapping("/x8/no-tx-publish")
    fun noTx() = mapOf("tag" to events.publishWithoutTx())
}

// ================================================================ [X10] 검증 실패 경로

data class X10Dto(@field:Positive val amount: Int, val name: String)

@RestController
class X10Controller {
    /** (a) @Valid @RequestBody, (d) Kotlin non-null 필드 누락·타입 오류 */
    @PostMapping("/x10/body")
    fun body(@Valid @RequestBody dto: X10Dto) = mapOf("ok" to dto.name, "amount" to dto.amount)

    /** (b) 파라미터 제약, 클래스 @Validated 없음 → MVC 내장 메서드 검증 */
    @GetMapping("/x10/param")
    fun param(@RequestParam @Positive n: Int) = mapOf("n" to n)
}

/** (c) 클래스에 @Validated → MethodValidationPostProcessor 의 AOP Proxy 가 검증한다. */
@Validated
@RestController
class X10ValidatedController {
    @GetMapping("/x10v/param")
    fun param(@RequestParam @Positive n: Int) = mapOf("n" to n)
}

// ================================================================ [X11] Security Filter 가 만드는 401·403

@Profile("secured")
@Configuration(proxyBeanMethods = false)
class ExcSecuredConfig {
    @Bean
    fun securedUsers() = InMemoryUserDetailsManager(
        User.withUsername("user").password("{noop}pw").roles("USER").build(),
    )

    @Bean
    fun securedChain(http: HttpSecurity, env: Environment, ctx: ApplicationContext): SecurityFilterChain {
        val errorPermit = env.getProperty("exc.sec.error-permit", Boolean::class.java, false)
        val tokenMode = env.getProperty("exc.sec.token-mode", "none")
        val delegate = env.getProperty("exc.sec.delegate", Boolean::class.java, false)
        http.csrf { it.disable() }
            .authorizeHttpRequests {
                if (errorPermit) it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                it.requestMatchers("/x11/admin").hasRole("ADMIN")
                it.anyRequest().authenticated()
            }
            .httpBasic(Customizer.withDefaults())
        if (delegate) {
            val d = DelegatingSecurityHandler(ctx)
            http.exceptionHandling { it.authenticationEntryPoint(d).accessDeniedHandler(d) }
        }
        when (tokenMode) {
            "throw" -> http.addFilterBefore(TokenFilter(null), UsernamePasswordAuthenticationFilter::class.java)
            "entrypoint" -> http.addFilterBefore(
                TokenFilter(BasicAuthenticationEntryPoint().apply { setRealmName("exc"); afterPropertiesSet() }),
                UsernamePasswordAuthenticationFilter::class.java,
            )
            // [X11AFTER] 같은 TokenFilter(던짐)를 ExceptionTranslationFilter 바로 뒤에 넣는다.
            "after" -> http.addFilterAfter(TokenFilter(null), ExceptionTranslationFilter::class.java)
        }
        ctx.getBeanProvider(CountingFilter::class.java).ifAvailable { f ->
            http.addFilterBefore(f, UsernamePasswordAuthenticationFilter::class.java)
        }
        ctx.getBeanProvider(CountingOnceFilter::class.java).ifAvailable { f ->
            http.addFilterBefore(f, UsernamePasswordAuthenticationFilter::class.java)
        }
        return http.build()
    }
}

@RestController
class X11Controller {
    @GetMapping("/x11/admin")
    fun admin() = mapOf("ok" to "admin")
}

/** 토큰 Filter. X-Token: expired 면 실패. entryPoint 가 null 이면 던지고, 있으면 직접 부른다. */
class TokenFilter(private val entryPoint: AuthenticationEntryPoint?) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.getHeader("X-Token") == "expired") {
            val ex = BadCredentialsException("token expired")
            if (entryPoint == null) throw ex
            entryPoint.commence(request, response, ex)
            return
        }
        chain.doFilter(request, response)
    }
}

/** EntryPoint·AccessDeniedHandler 가 handlerExceptionResolver 로 넘긴다(handler = null). */
class DelegatingSecurityHandler(private val ctx: ApplicationContext) : AuthenticationEntryPoint, AccessDeniedHandler {
    private fun resolver() = ctx.getBean("handlerExceptionResolver", HandlerExceptionResolver::class.java)
    override fun commence(request: HttpServletRequest, response: HttpServletResponse, ex: AuthenticationException) {
        val mav = resolver().resolveException(request, response, null, ex)
        calls += "commence(${ex.javaClass.simpleName}) -> resolveException returned ${if (mav == null) "null" else "ModelAndView"}"
    }
    override fun handle(request: HttpServletRequest, response: HttpServletResponse, ex: AccessDeniedException) {
        val mav = resolver().resolveException(request, response, null, ex)
        calls += "handle(${ex.javaClass.simpleName}) -> resolveException returned ${if (mav == null) "null" else "ModelAndView"}"
    }
    companion object { val calls = CopyOnWriteArrayList<String>() }
}

@Profile("sec-advice")
@Order(0)
@RestControllerAdvice
class SecurityProblemAdvice {
    @ExceptionHandler(AuthenticationException::class)
    fun auth(e: AuthenticationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.message ?: "")
    @ExceptionHandler(AccessDeniedException::class)
    fun denied(e: AccessDeniedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message ?: "")
}

@Profile("sec-advice-scoped")
@Order(0)
@RestControllerAdvice(assignableTypes = [ExcController::class])
class ScopedSecurityProblemAdvice {
    @ExceptionHandler(AuthenticationException::class)
    fun auth(e: AuthenticationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.message ?: "")
    @ExceptionHandler(AccessDeniedException::class)
    fun denied(e: AccessDeniedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message ?: "")
}

/** [X11 (7)] 평범한 Filter 를 @Component 로 두고 Security 체인에도 넣는다. */
@Profile("dup-filter")
@Component
class CountingFilter : Filter {
    override fun doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
        count.incrementAndGet()
        chain.doFilter(req, res)
    }
    companion object { val count = AtomicInteger() }
}

/** [X11 (7)] 같은 실험을 OncePerRequestFilter 로. */
@Profile("dup-filter")
@Component
class CountingOnceFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        count.incrementAndGet()
        chain.doFilter(request, response)
    }
    companion object { val count = AtomicInteger() }
}

// ================================================================ [X12] errorCode·traceId

class BusinessException(val code: String, message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.BAD_REQUEST)
class AnnotatedBadRequestException(message: String) : RuntimeException(message)

class OrderNotFoundException(id: String) : ErrorResponseException(
    HttpStatus.NOT_FOUND,
    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "order $id not found").apply {
        setProperty("errorCode", "ORDER_NOT_FOUND")
    },
    null,
)

@RestController
class X12Controller {
    @GetMapping("/x12/biz")
    fun biz(): Nothing = throw BusinessException("PAY_LIMIT_EXCEEDED", "limit exceeded")

    @GetMapping("/x12/rs-annotated")
    fun rsAnnotated(): Nothing = throw AnnotatedBadRequestException("annotated bad request")

    @GetMapping("/x12/rse")
    fun rse(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "rse bad request")

    @GetMapping("/x12/ere")
    fun ere(): Nothing = throw OrderNotFoundException("42")
}

private fun businessProblem(e: BusinessException, request: HttpServletRequest): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "").apply {
        setProperty("errorCode", e.code)
        setProperty("traceId", request.getHeader("X-Trace-Id") ?: "none")
    }

/**
 * (custom) super 를 부르기 전에 body 인자를 본다. 표준 예외는 body=null 로 들어오고
 * ProblemDetail 은 super 안(errorResponse.updateAndGetBody)에서 만들어지므로 errorCode 가 붙지 않는다.
 */
@Profile("custom")
@RestControllerAdvice
class CustomProblemAdvice : ResponseEntityExceptionHandler() {
    @ExceptionHandler(BusinessException::class)
    fun business(e: BusinessException, request: HttpServletRequest): ProblemDetail = businessProblem(e, request)

    override fun handleExceptionInternal(
        ex: Exception, body: Any?, headers: HttpHeaders, statusCode: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any>? {
        if (body is ProblemDetail && body.properties?.containsKey("errorCode") != true) {
            body.setProperty("errorCode", "STD_${statusCode.value()}")
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request)
    }
}

/** (custom-after) super 가 만든 ResponseEntity 의 body 에 붙인다. */
@Profile("custom-after")
@RestControllerAdvice
class CustomProblemAfterAdvice : ResponseEntityExceptionHandler() {
    @ExceptionHandler(BusinessException::class)
    fun business(e: BusinessException, request: HttpServletRequest): ProblemDetail = businessProblem(e, request)

    override fun handleExceptionInternal(
        ex: Exception, body: Any?, headers: HttpHeaders, statusCode: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any>? {
        val res = super.handleExceptionInternal(ex, body, headers, statusCode, request)
        val pd = res?.body as? ProblemDetail
        if (pd != null && pd.properties?.containsKey("errorCode") != true) {
            pd.setProperty("errorCode", "STD_${statusCode.value()}")
        }
        return res
    }
}

// ================================================================ [X13] /error 를 ProblemDetail 로, traceId

@Profile("error-attrs")
@Component
class ProblemErrorAttributes : DefaultErrorAttributes() {
    override fun getErrorAttributes(webRequest: WebRequest, options: ErrorAttributeOptions): MutableMap<String, Any?> {
        val base = super.getErrorAttributes(webRequest, options)
        return linkedMapOf(
            "type" to "about:blank",
            "title" to base["error"],
            "status" to base["status"],
            "detail" to (base["message"] ?: base["error"]),
            "instance" to base["path"],
            "traceId" to (MDC.get("traceId") ?: "none"),
        )
    }
}

@Profile("error-controller")
@RestController
class ProblemErrorController : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: 500
        val uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String ?: ""
        val pd = ProblemDetail.forStatus(status).apply {
            instance = URI.create(uri)
            setProperty("traceId", MDC.get("traceId") ?: "none")
        }
        return ResponseEntity.status(status).body(pd)
    }
}

object TraceIds {
    val seen = CopyOnWriteArrayList<String>()
    private val seq = AtomicInteger()
    fun next() = "t${seq.incrementAndGet()}"
}

/** (i)(ii) 평범한 Filter. 디스패치마다 새 id 를 만든다. */
class PlainTraceIdFilter : Filter {
    override fun doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
        val id = TraceIds.next()
        TraceIds.seen += "${(req as HttpServletRequest).dispatcherType} ${req.requestURI} traceId=$id"
        MDC.put("traceId", id)
        try { chain.doFilter(req, res) } finally { MDC.remove("traceId") }
    }
}

/** (iii) 기본 OncePerRequestFilter, (iv) 요청 속성 재사용 + shouldNotFilterErrorDispatch=false. */
class OnceTraceIdFilter(private val reuse: Boolean) : OncePerRequestFilter() {
    override fun shouldNotFilterErrorDispatch(): Boolean = if (reuse) false else super.shouldNotFilterErrorDispatch()
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = (if (reuse) request.getAttribute("exc.traceId") as String? else null) ?: TraceIds.next()
        request.setAttribute("exc.traceId", id)
        TraceIds.seen += "${request.dispatcherType} ${request.requestURI} traceId=$id"
        MDC.put("traceId", id)
        try { chain.doFilter(request, response) } finally { MDC.remove("traceId") }
    }
}

@Profile("traceid")
@Configuration(proxyBeanMethods = false)
class TraceIdConfig {
    @Bean
    fun traceIdFilter(env: Environment): FilterRegistrationBean<Filter> {
        val mode = env.getProperty("exc.trace.mode", "i")
        val filter: Filter = when (mode) {
            "i", "ii" -> PlainTraceIdFilter()
            "iii" -> OnceTraceIdFilter(reuse = false)
            else -> OnceTraceIdFilter(reuse = true)
        }
        return FilterRegistrationBean(filter).apply {
            addUrlPatterns("/*")
            order = Int.MIN_VALUE + 15            // TraceFilter(MIN+10) 다음, BoomFilter(MIN+20) 앞
            if (mode != "i") setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR)
        }
    }
}

// ================================================================ [X16] @PreAuthorize 거부와 catch-all

@Profile("methodsec")
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class ExcMethodSecConfig {
    @Bean
    fun methodSecUsers() = InMemoryUserDetailsManager(
        User.withUsername("user").password("{noop}pw").roles("USER").build(),
    )

    @Bean
    fun methodSecChain(http: HttpSecurity): SecurityFilterChain =
        http.csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .httpBasic(Customizer.withDefaults())
            .build()
}

@Profile("methodsec")
@RestController
class X16Controller {
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/x16/admin")
    fun admin() = mapOf("ok" to "admin")
}

/** MediaType 상수를 ExcProbes.kt 의 415 엔드포인트가 쓴다. */
internal const val JSON = MediaType.APPLICATION_JSON_VALUE

// ================================================================ [X11CTX] ERROR dispatch 에서 인증 상태는 어디서 오나

/**
 * Security Filter Chain(order -100) 앞(-110)과 뒤(-50)에서 dispatch 마다 인증 상태를 찍는다.
 * attr = RequestAttributeSecurityContextRepository 가 요청 속성에 둔 SecurityContext, holder = SecurityContextHolder.
 * dropAttrOnError 면 ERROR dispatch 가 Security 에 들어가기 전에 그 속성을 지운다(인과 확인용 변형).
 */
class SecCtxProbeFilter(private val where: String, private val dropAttrOnError: Boolean = false) : Filter {
    override fun doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
        val http = req as HttpServletRequest
        val key = org.springframework.security.web.context.RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME
        val attr = http.getAttribute(key) as org.springframework.security.core.context.SecurityContext?
        val holder = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication
        events += "$where ${http.dispatcherType} ${http.requestURI} attr=${if (attr == null) "absent" else desc(attr.authentication)} holder=${desc(holder)}"
        if (dropAttrOnError && http.dispatcherType == DispatcherType.ERROR && attr != null) {
            http.removeAttribute(key)
            events += "$where ${http.dispatcherType} removed attr"
        }
        chain.doFilter(req, res)
    }
    companion object {
        val events = CopyOnWriteArrayList<String>()
        fun desc(a: org.springframework.security.core.Authentication?) =
            a?.let { "${it.javaClass.simpleName}(${it.name})" } ?: "null"
    }
}

@Profile("ctx-probe")
@Configuration(proxyBeanMethods = false)
class SecCtxProbeConfig {
    @Bean
    fun ctxProbeBefore(env: Environment) = FilterRegistrationBean(
        SecCtxProbeFilter("before-security", env.getProperty("exc.sec.drop-ctx-attr", Boolean::class.java, false)),
    ).apply {
        setName("ctxProbeBefore")
        addUrlPatterns("/*")
        setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR)
        order = -110
    }

    @Bean
    fun ctxProbeAfter() = FilterRegistrationBean(SecCtxProbeFilter("after-security")).apply {
        setName("ctxProbeAfter")
        addUrlPatterns("/*")
        setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR)
        order = -50
    }
}

// ================================================================ [X7C] root 매치가 없을 때 CompletionException 의 cause 매치

/** IllegalStateException Handler 하나만 있는 advice. RuntimeException Handler 가 없어 CompletionException 에 root 매치가 없다. */
@Profile("ise-only")
@RestControllerAdvice
class IseOnlyAdvice {
    @ExceptionHandler(IllegalStateException::class)
    fun ise(e: IllegalStateException) = body(561, "IseOnlyAdvice#IllegalStateException", e)
}

// ================================================================ [X1UP] 업로드 크기 초과는 어디서 나나

/** [X1UP] multipart 업로드 하나를 받는다. spring.servlet.multipart.max-file-size 를 넘으면 checkMultipart 에서 실패한다. */
@RestController
class X1UpController {
    @PostMapping("/x1up/upload")
    fun upload(@RequestParam("file") file: MultipartFile) = mapOf("size" to file.size)
}

/**
 * [X1UP] Security Filter Chain(-100)보다 앞에서 업로드 요청의 getParameter 를 먼저 부른다.
 * CsrfFilter 처럼 앞쪽 Filter 가 파라미터를 읽어 multipart 파싱을 먼저 일으키는 구성을 흉내 낸다.
 */
@Profile("param-peek")
@Component
@Order(Int.MIN_VALUE + 30)
class ParamPeekFilter(env: Environment) : Filter {
    /** exc.peek.rethrow=true 면 getParameter 의 예외를 잡지 않고 그대로 던진다(보통의 Filter 처럼). */
    private val rethrow = env.getProperty("exc.peek.rethrow", Boolean::class.java, false)
    override fun doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
        val http = req as HttpServletRequest
        if (http.dispatcherType == DispatcherType.REQUEST && http.requestURI.startsWith("/x1up/")) {
            val r = runCatching { http.getParameter("peek") }
            events += if (r.isSuccess) "getParameter(\"peek\") returned ${r.getOrNull()} (no exception)"
                      else "getParameter threw ${RecordingResolver.causeChain(r.exceptionOrNull()!!)}" +
                          if (rethrow) " -> rethrown from filter" else " -> caught, chain continues"
            if (rethrow) r.getOrThrow()
        }
        chain.doFilter(req, res)
    }
    companion object { val events = CopyOnWriteArrayList<String>() }
}

// ================================================================ [X8IDEM] 멱등키 레이어가 commit 뒤 541 을 저장하면

/**
 * [X8IDEM] Stripe 식 멱등키 레이어를 흉내 낸 Filter. Idempotency-Key 헤더가 있으면 첫 실행의 상태·Content-Type·바디를 저장하고,
 * 같은 키가 다시 오면 체인을 부르지 않고 저장한 응답을 재생한다(Idempotent-Replayed: true).
 * exc.idem.store-5xx=false 면 5xx 응답은 저장하지 않는다(그 키의 재시도는 다시 실행된다).
 * 저장은 메모리 맵이다. 동시 요청 잠금(진행 중인 같은 키를 409 로 돌려보내는 것)은 이 실험의 관심이 아니라 넣지 않았다.
 */
@Profile("idem")
@Component
@Order(Int.MIN_VALUE + 40)
class IdempotencyFilter(env: Environment) : Filter {
    private val store5xx = env.getProperty("exc.idem.store-5xx", Boolean::class.java, true)
    override fun doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
        val http = req as HttpServletRequest
        val out = res as HttpServletResponse
        val key = http.getHeader("Idempotency-Key")
        if (key == null || http.dispatcherType != DispatcherType.REQUEST) { chain.doFilter(req, res); return }
        val saved = stored[key]
        if (saved != null) {
            events += "key=$key -> replay stored status=${saved.status} (chain not called)"
            out.status = saved.status
            saved.contentType?.let { out.contentType = it }
            out.setHeader("Idempotent-Replayed", "true")
            out.outputStream.write(saved.body)
            return
        }
        val wrapper = ContentCachingResponseWrapper(out)
        chain.doFilter(req, wrapper)
        val status = wrapper.status
        if (status < 500 || store5xx) {
            stored[key] = Saved(status, wrapper.contentType, wrapper.contentAsByteArray)
            events += "key=$key -> executed status=$status, stored"
        } else {
            events += "key=$key -> executed status=$status, not stored(5xx)"
        }
        wrapper.copyBodyToResponse()
    }
    data class Saved(val status: Int, val contentType: String?, val body: ByteArray)
    companion object {
        val stored = ConcurrentHashMap<String, Saved>()
        val events = CopyOnWriteArrayList<String>()
    }
}

/** [X8IDEM] 대조군 — 콜백에서 던지지 않고 commit 되는 요청(writeThenFail 의 phase 가 어느 콜백과도 맞지 않는다). */
@Profile("idem")
@RestController
class X8IdemController(private val svc: ExcService) {
    @GetMapping("/x8idem/ok")
    fun ok() = mapOf("tag" to svc.writeThenFail("idem-ok"))
}
