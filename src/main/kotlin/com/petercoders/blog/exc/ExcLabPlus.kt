// SPRING-INTERNALS 3편 — 2026-10 재편에서 더한 실험 러너(X1+·X2b·X3+·X5b·X7+·X8b·X10~X14·X16·X11CTX·X7C·X11AFTER·X1UP·X8IDEM).
// 진입점은 ExcLab.kt 의 main. 마커마다 새 로그 파일(runs/x1plus.log 등)로 찍는다.
// 각 마커는 첫 줄에 실행 JVM·Spring·Boot·Security·Tomcat 버전을 찍는다(로그 근거).
package com.petercoders.blog.exc

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.apache.catalina.util.ServerInfo
import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.boot.SpringBootVersion
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean
import org.springframework.boot.web.servlet.ServletContextInitializerBeans
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController
import org.springframework.boot.webmvc.error.ErrorAttributes
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.OrderComparator
import org.springframework.core.Ordered
import org.springframework.core.SpringVersion
import org.springframework.util.LinkedMultiValueMap
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.SpringSecurityCoreVersion
import org.springframework.security.web.FilterChainProxy
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.client.RestClient
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.ControllerAdviceBean
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

// ---------------------------------------------------------------- 공통 도구

internal data class Resp(val status: Int, val contentType: String, val body: String, val wwwAuth: String?)

internal val GET = HttpMethod.GET
internal val POST = HttpMethod.POST

internal fun call(
    method: HttpMethod, path: String,
    headers: Map<String, String> = emptyMap(),
    body: String? = null, contentType: String = MediaType.APPLICATION_JSON_VALUE,
): Resp {
    var spec: RestClient.RequestBodySpec = RestClient.create(base()).method(method).uri(path)
        .headers { h -> headers.forEach { (k, v) -> h.set(k, v) } }
    if (body != null) spec = spec.contentType(MediaType.parseMediaType(contentType)).body(body)
    return spec.exchange { _, res ->
        Resp(
            res.statusCode.value(),
            res.headers.contentType?.toString() ?: "-",
            res.bodyTo(String::class.java) ?: "",
            res.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE),
        )
    }!!
}

internal fun basic(user: String, pw: String) =
    "Basic " + Base64.getEncoder().encodeToString("$user:$pw".toByteArray())

internal fun showHead() {
    println("| case | status | content-type | WWW-Authenticate | body |")
    println("|---|---|---|---|---|")
}

internal fun show(case: String, r: Resp, max: Int = 300) =
    println("| $case | ${r.status} | ${r.contentType} | ${r.wwwAuth ?: "-"} | ${r.body.replace("\n", " ").take(max)} |")

internal fun envHeader(marker: String) {
    println("## [$marker]")
    println("env: java.runtime.version=${System.getProperty("java.runtime.version")} " +
        "java.vendor=${System.getProperty("java.vendor")} java.home=${System.getProperty("java.home")}")
    println("env: kotlin=${KotlinVersion.CURRENT} spring-framework=${SpringVersion.getVersion()} " +
        "spring-boot=${SpringBootVersion.getVersion()} spring-security=${SpringSecurityCoreVersion.getVersion()} " +
        "tomcat=${ServerInfo.getServerNumber()}")
}

private val json = JsonMapper.builder().build()

@Suppress("UNCHECKED_CAST")
internal fun jsonMap(s: String): Map<String, Any?> =
    runCatching { json.readValue(s, Map::class.java) as Map<String, Any?> }.getOrDefault(emptyMap())

private val whoRegex = Regex("\"who\":\"([^\"]+)\"")
private fun who(body: String) = whoRegex.find(body)?.groupValues?.get(1)?.let { " $it" } ?: ""
private fun shortCt(ct: String) = when {
    ct.contains("problem+json") -> "problem+json"
    ct.contains("json") -> "json"
    else -> ct
}

/**
 * root Logger 에 붙이는 수집 Appender. Logger 레벨을 통과한 이벤트만 들어온다.
 * Boot 가 기동할 때마다 Logback 을 다시 초기화하므로 boot() 뒤에 매번 install 한다.
 */
internal object LogCapture {
    val events = CopyOnWriteArrayList<String>()
    fun install() {
        val lc = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = lc.getLogger(Logger.ROOT_LOGGER_NAME)
        val app = object : AppenderBase<ILoggingEvent>() {
            override fun append(e: ILoggingEvent) {
                val first = e.formattedMessage.lineSequence().first().take(150)
                val t = e.throwableProxy?.let { " [+stacktrace ${it.className}]" } ?: ""
                events += "${e.level} ${e.loggerName.substringAfterLast('.')} : $first$t"
            }
        }
        app.context = lc
        app.name = "excCapture"
        app.start()
        root.addAppender(app)
    }
    fun clear() = events.clear()
    fun dump(indent: String = "  ") {
        println("${indent}log events captured: ${events.size}")
        events.forEach { println("$indent  - $it") }
    }
}

// ---------------------------------------------------------------- [X1+]

internal fun runX1Plus() {
    envHeader("X1+")
    println("Resolver·Filter 의 실제 order·dispatcher type 과 REEH 가 맡는 표준 예외 목록")
    boot("handlers", "reeh").use { ctx ->
        schema(ctx)
        println()
        println("### (1) HandlerExceptionResolver Bean 전부(OrderComparator 순)")
        println("| bean name | class | getOrder() |")
        println("|---|---|---|")
        ctx.getBeansOfType(HandlerExceptionResolver::class.java).entries
            .sortedWith { a, b -> OrderComparator.INSTANCE.compare(a.value, b.value) }
            .forEach { (n, r) -> println("| $n | ${r.javaClass.name} | ${(r as? Ordered)?.order ?: "(Ordered 아님)"} |") }
        call(GET, "/rows?tag=warmup")   // DispatcherServlet 은 첫 요청 때 전략을 초기화한다
        val ds = ctx.getBean(DispatcherServlet::class.java)
        val f = DispatcherServlet::class.java.getDeclaredField("handlerExceptionResolvers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val list = f.get(ds) as List<HandlerExceptionResolver>?
        println("DispatcherServlet.handlerExceptionResolvers (첫 요청 뒤, doDispatch 가 이 순서로 묻는다):")
        list?.forEachIndexed { i, r -> println("  [$i] ${r.javaClass.name} order=${(r as? Ordered)?.order}") }
        val composite = ctx.getBean("handlerExceptionResolver") as HandlerExceptionResolverComposite
        println("handlerExceptionResolver(Composite) 안쪽:")
        composite.exceptionResolvers.forEachIndexed { i, r -> println("  [$i] ${r.javaClass.name}") }

        println()
        println("### (2) 서블릿 컨테이너에 등록되는 Filter(ServletContextInitializerBeans 순서 = 실행 순서)")
        println("| filter name | registration class | order | dispatcher types | url patterns |")
        println("|---|---|---|---|---|")
        ServletContextInitializerBeans(ctx.beanFactory).forEach { i ->
            if (i is AbstractFilterRegistrationBean<*>) {
                println("| ${i.filterName} | ${i.javaClass.simpleName} | ${i.order} | ${i.determineDispatcherTypes()} | ${i.urlPatterns} |")
            }
        }
        val fcp = ctx.getBean("springSecurityFilterChain") as FilterChainProxy
        fcp.filterChains.forEachIndexed { ci, c ->
            println("Security Filter Chain[$ci] (permitAll 체인) 안의 Filter:")
            c.filters.forEachIndexed { j, fl -> println("  [$j] ${fl.javaClass.simpleName}") }
        }

        println()
        val m = ResponseEntityExceptionHandler::class.java
            .getMethod("handleException", Exception::class.java, WebRequest::class.java)
        val types = m.getAnnotation(ExceptionHandler::class.java).value
        println("### (3) ResponseEntityExceptionHandler.handleException 의 @ExceptionHandler 목록: ${types.size}개")
        types.forEachIndexed { i, t -> println("  ${i + 1}. ${t.java.name}") }
        println("MaxUploadSizeExceededException 포함 = ${types.any { it == MaxUploadSizeExceededException::class }}")

        println()
        println("### (4) advice Bean 과 order(ControllerAdviceBean.findAnnotatedBeans 는 order 순으로 정렬해 돌려준다)")
        ControllerAdviceBean.findAnnotatedBeans(ctx).forEach {
            println("  ${it.beanType?.simpleName} order=${it.order}")
        }
    }
}

// ---------------------------------------------------------------- [X2b]

internal fun runX2b() {
    envHeader("X2b")
    println("상태 코드가 곧 받은 advice 다.")
    println("  571/572/573 = ScopedAdvice(@Order(10), assignableTypes=[ExcController]) 405/404/415")
    println("  581/582/583 = UnscopedAdvice(@Order(20), 범위 제한 없음) 405/404/415")
    println("  591 = OrAdvice(@Order(1), basePackages=[no.such.pkg] + assignableTypes=[ExcController]) ISE")
    val text = "text/plain"
    for (profile in listOf("scoped", "scoped-only")) {
        boot(profile).use { ctx ->
            println()
            println("### profile=$profile")
            println("advice beans = " + ControllerAdviceBean.findAnnotatedBeans(ctx)
                .joinToString { "${it.beanType?.simpleName}(order=${it.order})" })
            schema(ctx)
            showHead()
            show("405 POST /x2/ise (Handler 찾기 전)", call(POST, "/x2/ise"))
            show("404 GET /x4/nope (정적 리소스 Handler)", call(GET, "/x4/nope"))
            show("ISE GET /x2/ise (ExcController)", call(GET, "/x2/ise"))
            show("ISE GET /x2b/other-ise (X2bOtherController)", call(GET, "/x2b/other-ise"))
            show("415 POST /x2b/consumes text/plain (매핑 단계)", call(POST, "/x2b/consumes", body = "hello", contentType = text))
            show("415 POST /x2b/body text/plain (인자 해석 단계)", call(POST, "/x2b/body", body = "hello", contentType = text))
        }
    }
}

// ---------------------------------------------------------------- [X3+]

private class Cfg(val label: String, val profiles: Array<String>, val props: Array<String>)

internal fun runX3Plus() {
    envHeader("X3+")
    println("구성 8개 × 요청 4개. 599 = catch-all advice 가 받음, 541 은 이 실험에 없다(handlers 프로파일을 켜지 않는다).")
    val pd = arrayOf("spring.mvc.problemdetails.enabled=true")
    val none = arrayOf<String>()
    val cfgs = listOf(
        Cfg("default", none, none),
        Cfg("problemdetails", none, pd),
        Cfg("reeh", arrayOf("reeh"), none),
        Cfg("problemdetails+reeh", arrayOf("reeh"), pd),
        Cfg("catchall", arrayOf("catchall"), none),
        Cfg("catchall+problemdetails", arrayOf("catchall"), pd),
        Cfg("catchall-highest+problemdetails", arrayOf("catchall-highest"), pd),
        Cfg("reeh-with-catchall", arrayOf("reeh-catchall"), none),
    )
    val reqs = listOf(
        Triple("400 GET /x4/param", GET, "/x4/param"),
        Triple("405 POST /x2/ise", POST, "/x2/ise"),
        Triple("404 GET /x4/nope", GET, "/x4/nope"),
        Triple("ISE GET /x2/ise", GET, "/x2/ise"),
    )
    val matrix = linkedMapOf<String, List<String>>()
    for (c in cfgs) {
        boot(*c.profiles, props = c.props).use { ctx ->
            println()
            println("### ${c.label}")
            println("resolved spring.mvc.problemdetails.enabled = ${ctx.environment.getProperty("spring.mvc.problemdetails.enabled")}")
            println("beans matching 'roblemDetails' = ${ctx.beanDefinitionNames.filter { it.contains("roblemDetails") }}")
            println("advice beans = " + ControllerAdviceBean.findAnnotatedBeans(ctx)
                .joinToString { "${it.beanType?.simpleName}(order=${it.order})" })
            schema(ctx)
            showHead()
            matrix[c.label] = reqs.map { (label, m, p) ->
                val r = call(m, p)
                show("${c.label} $label", r)
                "${r.status} ${shortCt(r.contentType)}${who(r.body)}"
            }
        }
    }
    println()
    println("### 요약 매트릭스(status content-type [advice])")
    println("| 구성 | " + reqs.joinToString(" | ") { it.first } + " |")
    println("|---|" + reqs.joinToString("") { "---|" })
    matrix.forEach { (k, v) -> println("| $k | " + v.joinToString(" | ") + " |") }
}

// ---------------------------------------------------------------- [X5b]

internal fun runX5b() {
    envHeader("X5b")
    println("Filter 예외(/x5/filter-boom)의 /error 바디 키. boot() 는 언제나 옛 키 server.error.include-message=always 를 넣는다(대조군).")
    val cases = listOf(
        "A 옛 키만(server.error.include-message=always)" to arrayOf<String>(),
        "B 새 키 spring.web.error.include-message=always" to arrayOf("spring.web.error.include-message=always"),
        "C 새 키 include-exception=true, include-stacktrace=always" to arrayOf(
            "spring.web.error.include-exception=true", "spring.web.error.include-stacktrace=always"),
        "D 옛 키 server.error.include-exception=true, include-stacktrace=always" to arrayOf(
            "server.error.include-exception=true", "server.error.include-stacktrace=always"),
    )
    for ((label, props) in cases) {
        boot("handlers", props = props).use { ctx ->
            println()
            println("### $label")
            listOf("server.error.include-message", "spring.web.error.include-message",
                "spring.web.error.include-exception", "spring.web.error.include-stacktrace",
                "server.error.include-exception", "server.error.include-stacktrace").forEach {
                println("  env $it = ${ctx.environment.getProperty(it)}")
            }
            val r = call(GET, "/x5/filter-boom")
            val m = jsonMap(r.body)
            println("  status=${r.status} content-type=${r.contentType}")
            println("  body keys = ${m.keys}")
            println("  message = ${m["message"]}")
            println("  exception = ${m["exception"]}")
            println("  trace(앞 120자) = ${(m["trace"] as String?)?.replace("\n", " | ")?.take(120)}")
        }
    }
}

// ---------------------------------------------------------------- [X7+]

internal fun runX7Plus() {
    envHeader("X7+")
    boot("handlers").use { ctx ->
        schema(ctx)
        LogCapture.install()
        println("### 대조군: @Async void")
        LogCapture.clear()
        val v = call(GET, "/x7/async-void")
        Thread.sleep(500)
        println("  status=${v.status} body=${v.body}")
        LogCapture.dump()

        println("### 기다리지 않은 CompletableFuture")
        LogCapture.clear()
        X7PlusController.lastFuture = null
        val r = call(GET, "/x7plus/future-kept")
        val fut = X7PlusController.lastFuture!!
        val deadline = System.currentTimeMillis() + 2000
        while (!fut.isDone && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(500)
        println("  status=${r.status} body=${r.body}")
        println("  future.isDone=${fut.isDone} isCompletedExceptionally=${fut.isCompletedExceptionally}")
        LogCapture.dump()
        val thrown = runCatching { fut.join() }.exceptionOrNull()
        println("  (응답 뒤 러너가 직접) join() -> ${thrown?.javaClass?.name}, cause=${thrown?.cause?.javaClass?.name}: ${thrown?.cause?.message}")
    }
}

// ---------------------------------------------------------------- [X8b][X8c][X8d]

internal fun runX8b() {
    envHeader("X8b")
    boot("handlers").use { ctx ->
        schema(ctx)
        LogCapture.install()
        println("### [X8b] @TransactionalEventListener 가 던질 때(541 = AdviceA 가 받음)")
        for ((label, path, tag) in listOf(
            Triple("AFTER_COMMIT listener", "/x8/listener-after-commit", "listener-AFTER_COMMIT"),
            Triple("BEFORE_COMMIT listener", "/x8/listener-before-commit", "listener-BEFORE_COMMIT"),
            Triple("AFTER_COMMIT forPayload + SynchronizationCallback", "/x8/listener-callback", "listener-callback"),
        )) {
            LogCapture.clear()
            val r = call(GET, path)
            Thread.sleep(200)
            showHead()
            show(label, r)
            println("  rows($tag) = ${call(GET, "/rows?tag=$tag").body}")
            LogCapture.dump()
        }
        println("  SynchronizationCallback 기록 = ${ExcCallbackListenerConfig.callbackSeen}")

        println()
        println("### [X8c] 트랜잭션 없이 publish 했을 때 AFTER_COMMIT 리스너 호출 횟수")
        val d0 = ExcTxListeners.defaultCalls.get()
        val f0 = ExcTxListeners.fallbackCalls.get()
        LogCapture.clear()
        val r = call(GET, "/x8/no-tx-publish")
        Thread.sleep(200)
        println("  status=${r.status} body=${r.body}")
        println("  fallbackExecution=false(기본) 리스너 호출 = ${ExcTxListeners.defaultCalls.get() - d0}")
        println("  fallbackExecution=true 리스너 호출 = ${ExcTxListeners.fallbackCalls.get() - f0}")
        LogCapture.dump()

        println()
        println("### [X8d] afterCommit 에서 던지는 요청을 두 번 보낸 뒤 행 수(재시도 시뮬레이션)")
        schema(ctx)   // 표를 새로 만든다
        println("  rows(afterCommit) before = ${call(GET, "/rows?tag=afterCommit").body}")
        showHead()
        show("1st GET /x8/after-commit", call(GET, "/x8/after-commit"))
        show("2nd GET /x8/after-commit (retry)", call(GET, "/x8/after-commit"))
        println("  rows(afterCommit) after = ${call(GET, "/rows?tag=afterCommit").body}")

        println()
        println("### [X8e] @Async + @TransactionalEventListener(BEFORE_COMMIT) 리스너가 던질 때(리스너가 다른 Thread 에서 돈다)")
        LogCapture.clear()
        showHead()
        show("@Async BEFORE_COMMIT listener", call(GET, "/x8/listener-async-before-commit"))
        Thread.sleep(500)
        println("  rows(listener-ASYNC_BEFORE_COMMIT) = ${call(GET, "/rows?tag=listener-ASYNC_BEFORE_COMMIT").body}")
        LogCapture.dump()
    }
}

// ---------------------------------------------------------------- [X10]

internal fun runX10() {
    envHeader("X10")
    println("resolver saw = DispatcherServlet 이 Resolver 목록에 넘긴 예외(HIGHEST_PRECEDENCE 기록용 Resolver, 응답에 손대지 않음)")
    val adapt = arrayOf("spring.validation.method.adapt-constraint-violations=true")
    val cfgs = listOf(
        Cfg("default", arrayOf("recorder"), arrayOf()),
        Cfg("reeh", arrayOf("recorder", "reeh"), arrayOf()),
        Cfg("default+adapt", arrayOf("recorder"), adapt),
        Cfg("reeh+adapt", arrayOf("recorder", "reeh"), adapt),
    )
    data class Q(val label: String, val method: HttpMethod, val path: String, val body: String? = null)
    val qs = listOf(
        Q("control valid body", POST, "/x10/body", """{"amount":1,"name":"a"}"""),
        Q("(a) @Valid @RequestBody amount=-1", POST, "/x10/body", """{"amount":-1,"name":"a"}"""),
        Q("(b) @Positive @RequestParam n=-1", GET, "/x10/param?n=-1"),
        Q("(c) class @Validated n=-1", GET, "/x10v/param?n=-1"),
        Q("(d1) non-null name missing", POST, "/x10/body", """{"amount":1}"""),
        Q("(d2) amount wrong type", POST, "/x10/body", """{"amount":"abc","name":"a"}"""),
        Q("(d3) Int amount missing", POST, "/x10/body", """{"name":"a"}"""),
    )
    for (c in cfgs) {
        boot(*c.profiles, props = c.props).use { ctx ->
            println()
            println("### ${c.label}")
            println("resolved spring.validation.method.adapt-constraint-violations = " +
                ctx.environment.getProperty("spring.validation.method.adapt-constraint-violations"))
            println("X10ValidatedController is AOP proxy = ${AopUtils.isAopProxy(ctx.getBean(X10ValidatedController::class.java))}")
            schema(ctx)
            showHead()
            for (q in qs) {
                RecordingResolver.seen.clear()
                val r = call(q.method, q.path, body = q.body)
                show("${c.label} ${q.label}", r, 400)
                println("  resolver saw: ${RecordingResolver.seen}")
            }
        }
    }
}

// ---------------------------------------------------------------- [X11]

private data class Req(val label: String, val path: String, val headers: Map<String, String> = emptyMap())

private fun runReqs(reqs: List<Req>) {
    showHead()
    for (q in reqs) {
        TraceFilter.events.clear()
        RecordingResolver.seen.clear()
        DelegatingSecurityHandler.calls.clear()
        val r = call(GET, q.path, q.headers)
        show(q.label, r)
        println("  dispatch: ${TraceFilter.events}")
        if (RecordingResolver.seen.isNotEmpty()) println("  resolver saw: ${RecordingResolver.seen}")
        if (DelegatingSecurityHandler.calls.isNotEmpty()) println("  delegate: ${DelegatingSecurityHandler.calls}")
    }
}

private fun printChain(ctx: ConfigurableApplicationContext) {
    val fcp = ctx.getBean("springSecurityFilterChain") as FilterChainProxy
    fcp.filterChains.forEachIndexed { ci, c ->
        println("Security Filter Chain[$ci] 안의 Filter(실행 순서):")
        c.filters.forEachIndexed { j, fl -> println("  [$j] ${fl.javaClass.name}") }
    }
}

internal fun runX11() {
    envHeader("X11")
    println("user = httpBasic user/pw(ROLE_USER). /x11/admin 은 hasRole('ADMIN'). 541 = AdviceA(handlers) 가 받음.")
    val user = mapOf("Authorization" to basic("user", "pw"))
    val expiredAnon = mapOf("X-Token" to "expired")
    val expiredUser = user + expiredAnon

    for (ep in listOf(false, true)) {
        boot("secured", "handlers", "recorder", props = arrayOf("exc.sec.error-permit=$ep")).use { ctx ->
            println()
            println("### (1)(1b)(2)(3) profile=secured,handlers  ERROR dispatch permitAll=$ep")
            if (!ep) printChain(ctx)
            schema(ctx)
            runReqs(listOf(
                Req("(1) anonymous GET /x2/ise", "/x2/ise"),
                Req("(1b) user GET /x11/admin", "/x11/admin", user),
                Req("(2) user GET /x2/ise", "/x2/ise", user),
                Req("(3a) anonymous GET /x5/filter-boom", "/x5/filter-boom"),
                Req("(3b) user GET /x5/filter-boom", "/x5/filter-boom", user),
            ))
        }
    }
    for (mode in listOf("throw", "entrypoint")) {
        for (ep in listOf(false, true)) {
            boot("secured", "handlers", "recorder",
                props = arrayOf("exc.sec.error-permit=$ep", "exc.sec.token-mode=$mode")).use { ctx ->
                println()
                val n = if (mode == "throw") "(4) 토큰 Filter 가 던짐" else "(5) 토큰 Filter 가 EntryPoint 를 직접 부름"
                println("### $n — addFilterBefore(UsernamePasswordAuthenticationFilter)  ERROR dispatch permitAll=$ep")
                if (!ep) printChain(ctx)
                schema(ctx)
                runReqs(listOf(
                    Req("anonymous + X-Token: expired GET /x2/ise", "/x2/ise", expiredAnon),
                    Req("user + X-Token: expired GET /x2/ise", "/x2/ise", expiredUser),
                ))
            }
        }
    }
    for (adv in listOf("sec-advice", "sec-advice-scoped")) {
        boot("secured", adv, "recorder",
            props = arrayOf("exc.sec.error-permit=true", "exc.sec.delegate=true")).use { ctx ->
            println()
            println("### (6) EntryPoint·AccessDeniedHandler → handlerExceptionResolver.resolveException(req, res, null, ex)  profile=$adv")
            println("advice beans = " + ControllerAdviceBean.findAnnotatedBeans(ctx)
                .joinToString { "${it.beanType?.simpleName}(order=${it.order})" })
            schema(ctx)
            runReqs(listOf(
                Req("(6) anonymous GET /x2/ise", "/x2/ise"),
                Req("(6) user GET /x11/admin", "/x11/admin", user),
            ))
        }
    }
    boot("secured", "handlers", "dup-filter", props = arrayOf("exc.sec.error-permit=true")).use { ctx ->
        println()
        println("### (7) @Component Filter 를 addFilterBefore 로 Security 체인에도 넣었을 때 요청 1건당 호출 횟수")
        printChain(ctx)
        schema(ctx)
        CountingFilter.count.set(0)
        CountingOnceFilter.count.set(0)
        val r = call(GET, "/x2/ise", user)
        println("  user GET /x2/ise -> ${r.status}")
        println("  CountingFilter(평범한 Filter) 호출 = ${CountingFilter.count.get()}")
        println("  CountingOnceFilter(OncePerRequestFilter) 호출 = ${CountingOnceFilter.count.get()}")
    }
    boot("secured", "handlers", "error-controller", "recorder", props = arrayOf("exc.sec.error-permit=true")).use { ctx ->
        println()
        println("### (8) secured + error-controller(/error 가 ProblemDetail 반환) + ERROR dispatch permitAll=true")
        schema(ctx)
        runReqs(listOf(
            Req("(1) anonymous GET /x2/ise", "/x2/ise"),
            Req("(1b) user GET /x11/admin", "/x11/admin", user),
            Req("(3b) user GET /x5/filter-boom", "/x5/filter-boom", user),
        ))
    }
}

// ---------------------------------------------------------------- [X12]

internal fun runX12() {
    envHeader("X12")
    val reqs = listOf(
        Req("BusinessException GET /x12/biz (X-Trace-Id: t-123)", "/x12/biz", mapOf("X-Trace-Id" to "t-123")),
        Req("표준 예외 400 GET /x4/param", "/x4/param"),
        Req("@ResponseStatus 예외 GET /x12/rs-annotated", "/x12/rs-annotated"),
        Req("ResponseStatusException GET /x12/rse", "/x12/rse"),
        Req("ErrorResponseException 하위 GET /x12/ere", "/x12/ere"),
    )
    // custom: handleExceptionInternal 에서 super 전에 body 인자를 본다 / custom-after: super 결과의 body 에 붙인다
    for (profiles in listOf(arrayOf("custom", "recorder"), arrayOf("custom-after", "recorder"), arrayOf("recorder"))) {
        boot(*profiles).use { ctx ->
            println()
            println("### profile=${profiles.joinToString(",")}")
            println("advice beans = " + ControllerAdviceBean.findAnnotatedBeans(ctx)
                .joinToString { "${it.beanType?.simpleName}(order=${it.order})" })
            schema(ctx)
            runReqs(reqs)
        }
    }
}

// ---------------------------------------------------------------- [X13]

internal fun runX13() {
    envHeader("X13")
    for (profile in listOf("(none)", "error-attrs", "error-controller")) {
        val ps = if (profile == "(none)") arrayOf() else arrayOf(profile)
        boot(*ps).use { ctx ->
            println()
            println("### profile=$profile")
            println("BasicErrorController beans = ${ctx.getBeanNamesForType(BasicErrorController::class.java).toList()}")
            println("ErrorController beans = ${ctx.getBeanNamesForType(ErrorController::class.java).toList()}")
            println("ErrorAttributes beans = " + ctx.getBeansOfType(ErrorAttributes::class.java)
                .map { (n, b) -> "$n:${b.javaClass.simpleName}" })
            schema(ctx)
            runReqs(listOf(
                Req("Filter 예외 GET /x5/filter-boom", "/x5/filter-boom"),
                Req("advice 없는 ISE GET /x2/ise", "/x2/ise"),
                Req("DHER sendError 400 GET /x4/param", "/x4/param"),
            ))
        }
    }
    println()
    println("### traceId 네 변형(profile=traceid,error-controller). /error 바디 traceId 가 REQUEST 단계 id 와 같은가")
    val labels = mapOf(
        "i" to "(i) 평범한 Filter, REQUEST 만 등록",
        "ii" to "(ii) 같은 Filter 에 ERROR 포함",
        "iii" to "(iii) OncePerRequestFilter 에 ERROR 포함(shouldNotFilterErrorDispatch 기본)",
        "iv" to "(iv) OncePerRequestFilter, 요청 속성 재사용 + shouldNotFilterErrorDispatch=false",
    )
    for ((mode, label) in labels) {
        boot("traceid", "error-controller", props = arrayOf("exc.trace.mode=$mode")).use { ctx ->
            schema(ctx)
            println()
            println("#### $label")
            for (path in listOf("/x5/filter-boom", "/x2/ise")) {
                TraceIds.seen.clear()
                val r = call(GET, path)
                println("  GET $path -> ${r.status} ${r.contentType} body.traceId=${jsonMap(r.body)["traceId"]}")
                println("    filter 기록: ${TraceIds.seen}")
            }
        }
    }
}

// ---------------------------------------------------------------- [X14]

internal fun runX14() {
    envHeader("X14")
    println("요청마다 root Logger 에 들어온 로그 이벤트를 센다(이벤트 1건 = 로그 1건, 스택트레이스는 [+stacktrace] 로 표시).")
    val cfgs = listOf(
        "A logging.level.root=WARN(기존 실험과 같음)" to arrayOf<String>(),
        "B logging.level.root=INFO" to arrayOf("logging.level.root=INFO"),
        "C root=WARN + spring.mvc.log-resolved-exception=true" to arrayOf("spring.mvc.log-resolved-exception=true"),
    )
    for ((label, props) in cfgs) {
        println()
        println("### $label")
        boot("handlers", props = props).use { ctx ->
            schema(ctx)
            LogCapture.install()
            call(GET, "/rows?tag=warmup")
            Thread.sleep(200)
            for ((name, m, p) in listOf(
                Triple("advice 처리 541 GET /x2/ise", GET, "/x2/ise"),
                Triple("DHER 처리 400 GET /x4/param", GET, "/x4/param"),
                Triple("삼켜진 afterCompletion GET /x8/after-completion", GET, "/x8/after-completion"),
                Triple("Filter 500 GET /x5/filter-boom", GET, "/x5/filter-boom"),
                Triple("@Async void GET /x7/async-void", GET, "/x7/async-void"),
            )) {
                LogCapture.clear()
                val r = call(m, p)
                Thread.sleep(300)
                println("- $name -> ${r.status}")
                LogCapture.dump("    ")
            }
        }
        boot("handlers", "reeh", props = props).use { ctx ->
            schema(ctx)
            LogCapture.install()
            call(GET, "/rows?tag=warmup")
            Thread.sleep(200)
            for ((name, m, p) in listOf(
                Triple("REEH 처리 405 POST /x2/ise", POST, "/x2/ise"),
                Triple("REEH 처리 400 GET /x4/param", GET, "/x4/param"),
            )) {
                LogCapture.clear()
                val r = call(m, p)
                Thread.sleep(300)
                println("- $name -> ${r.status}")
                LogCapture.dump("    ")
            }
        }
    }
}

// ---------------------------------------------------------------- [X16]

internal fun runX16() {
    envHeader("X16")
    println("/x16/admin 은 @PreAuthorize(\"hasRole('ADMIN')\"). URL 인가는 permitAll, httpBasic user/pw(ROLE_USER).")
    println("541 = AdviceA(RuntimeException), 599 = catch-all advice")
    val user = mapOf("Authorization" to basic("user", "pw"))
    for ((label, profiles) in listOf(
        "advice 없음" to arrayOf("methodsec", "recorder"),
        "handlers(AdviceA @Order(1) RuntimeException)" to arrayOf("methodsec", "handlers", "recorder"),
        "catchall(Exception, @Order 없음)" to arrayOf("methodsec", "catchall", "recorder"),
        "catchall-rethrow(AccessDeniedException 은 되던짐)" to arrayOf("methodsec", "catchall-rethrow", "recorder"),
    )) {
        boot(*profiles).use { ctx ->
            println()
            println("### $label  profile=${profiles.joinToString(",")}")
            println("X16Controller is AOP proxy = ${AopUtils.isAopProxy(ctx.getBean(X16Controller::class.java))}")
            schema(ctx)
            runReqs(listOf(
                Req("anonymous GET /x16/admin", "/x16/admin"),
                Req("user GET /x16/admin", "/x16/admin", user),
            ))
        }
    }
}

// ---------------------------------------------------------------- [X11CTX]

/** X11 (1)(1b) 를 ERROR dispatch 인가 허용 없이 다시 보내며, dispatch 마다 SecurityContext 가 어디서 오는지 찍는다. */
internal fun runX11Ctx() {
    envHeader("X11CTX")
    println("ERROR dispatch 인가 허용 없음(exc.sec.error-permit=false), httpBasic user/pw(ROLE_USER). /x11/admin 은 hasRole('ADMIN').")
    println("probe: before-security(order -110) / after-security(order -50), REQUEST·ERROR 로 등록. attr = 요청 속성의 SecurityContext")
    val user = mapOf("Authorization" to basic("user", "pw"))
    for (drop in listOf(false, true)) {
        boot("secured", "ctx-probe",
            props = arrayOf("exc.sec.error-permit=false", "exc.sec.drop-ctx-attr=$drop")).use { ctx ->
            println()
            println("### exc.sec.drop-ctx-attr=$drop" +
                if (drop) "  (ERROR dispatch 가 Security 에 들어가기 전에 요청 속성의 SecurityContext 를 지운다)" else "")
            if (!drop) printContextRepos(ctx)
            schema(ctx)
            showHead()
            for (q in listOf(
                Req("(1) anonymous GET /x2/ise", "/x2/ise"),
                Req("(1b) user GET /x11/admin", "/x11/admin", user),
            )) {
                TraceFilter.events.clear()
                SecCtxProbeFilter.events.clear()
                val r = call(GET, q.path, q.headers)
                show(q.label, r)
                println("  dispatch: ${TraceFilter.events}")
                SecCtxProbeFilter.events.forEach { println("  probe: $it") }
            }
        }
    }
}

private fun printContextRepos(ctx: ConfigurableApplicationContext) {
    fun field(o: Any, cls: Class<*>, name: String): Any? =
        runCatching { cls.getDeclaredField(name).apply { isAccessible = true }.get(o) }.getOrNull()
    fun repoDesc(repo: Any?): String {
        if (repo == null) return "null"
        val delegates = field(repo, repo.javaClass, "delegates") as List<*>?
        return repo.javaClass.simpleName + (delegates?.joinToString(prefix = "[", postfix = "]") { it?.javaClass?.simpleName ?: "null" } ?: "")
    }
    val fcp = ctx.getBean("springSecurityFilterChain", FilterChainProxy::class.java)
    for (f in fcp.filterChains.first().filters) {
        when (f) {
            is org.springframework.security.web.context.SecurityContextHolderFilter ->
                println("SecurityContextHolderFilter.securityContextRepository = " +
                    repoDesc(field(f, f.javaClass, "securityContextRepository")))
            is org.springframework.security.web.authentication.www.BasicAuthenticationFilter -> {
                println("BasicAuthenticationFilter.securityContextRepository = " +
                    repoDesc(field(f, f.javaClass, "securityContextRepository")))
                val m = org.springframework.web.filter.OncePerRequestFilter::class.java
                    .getDeclaredMethod("shouldNotFilterErrorDispatch").apply { isAccessible = true }
                println("BasicAuthenticationFilter is OncePerRequestFilter = true, shouldNotFilterErrorDispatch() = ${m.invoke(f)}")
            }
            is org.springframework.security.web.access.intercept.AuthorizationFilter ->
                println("AuthorizationFilter.filterErrorDispatch = ${field(f, f.javaClass, "filterErrorDispatch")}")
        }
    }
}

// ---------------------------------------------------------------- [X7C]

/** future joined 를 advice 구성 둘로: AdviceA(RuntimeException, root 매치 있음) / IseOnlyAdvice(IllegalStateException 만, root 매치 없음). */
internal fun runX7c() {
    envHeader("X7C")
    for ((label, profiles) in listOf(
        "handlers(AdviceA RuntimeException @Order(1) + AdviceB IllegalStateException @Order(2))" to arrayOf("handlers"),
        "ise-only(IseOnlyAdvice IllegalStateException 만)" to arrayOf("ise-only"),
    )) {
        boot(*profiles).use { ctx ->
            println()
            println("### $label")
            println("advice beans = " + ControllerAdviceBean.findAnnotatedBeans(ctx)
                .joinToString { "${it.beanType?.simpleName}(order=${it.order})" })
            schema(ctx)
            showHead()
            show("future joined GET /x7/async-join", call(GET, "/x7/async-join"))
            Thread.sleep(300)
        }
    }
}

// ---------------------------------------------------------------- [X11AFTER]

/** X11 (4) 의 TokenFilter(던짐)를 ExceptionTranslationFilter 바로 뒤(addFilterAfter)에 넣고 같은 두 요청을 보낸다. */
internal fun runX11After() {
    envHeader("X11AFTER")
    println("user = httpBasic user/pw(ROLE_USER). X-Token: expired 면 TokenFilter 가 BadCredentialsException 을 던진다.")
    val user = mapOf("Authorization" to basic("user", "pw"))
    val expiredAnon = mapOf("X-Token" to "expired")
    val expiredUser = user + expiredAnon
    for (ep in listOf(false, true)) {
        boot("secured", "handlers", "recorder",
            props = arrayOf("exc.sec.error-permit=$ep", "exc.sec.token-mode=after")).use { ctx ->
            println()
            println("### 토큰 Filter 가 던짐 — addFilterAfter(ExceptionTranslationFilter)  ERROR dispatch permitAll=$ep")
            printChain(ctx)
            schema(ctx)
            runReqs(listOf(
                Req("anonymous + X-Token: expired GET /x2/ise", "/x2/ise", expiredAnon),
                Req("user + X-Token: expired GET /x2/ise", "/x2/ise", expiredUser),
            ))
        }
    }
}

// ---------------------------------------------------------------- [X1UP]

private fun upload(path: String, bytes: Int): Resp {
    val parts = LinkedMultiValueMap<String, Any>()
    parts.add("file", object : ByteArrayResource(ByteArray(bytes) { 'a'.code.toByte() }) {
        override fun getFilename() = "big.txt"
    })
    return RestClient.create(base()).post().uri(path)
        .contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
        .exchange { _, res ->
            Resp(res.statusCode.value(), res.headers.contentType?.toString() ?: "-",
                res.bodyTo(String::class.java) ?: "", res.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
        }!!
}

/** spring.servlet.multipart.max-file-size=1KB 에 4KB 파일을 올린다. 예외가 DispatcherServlet 의 Resolver 목록까지 오는지(= try 안) 본다. */
internal fun runX1Up() {
    envHeader("X1UP")
    println("spring.servlet.multipart.max-file-size=1KB, 올리는 파일 4096 bytes. 기본 체인(permitAll, csrf off).")
    println("resolver saw = DispatcherServlet.processHandlerException 이 Resolver 목록에 넘긴 예외(RecordingResolver, HIGHEST_PRECEDENCE)")
    println("resolver saw 의 앞 단어는 그 예외를 받은 dispatch 의 type 이다(REQUEST / ERROR).")
    RecordingResolver.withDispatch = true
    for ((label, profiles, rethrow) in listOf(
        Triple("advice 없음", arrayOf("recorder"), false),
        Triple("REEH 상속(reeh)", arrayOf("reeh", "recorder"), false),
        Triple("REEH 상속 + Security 앞 Filter 가 getParameter 를 먼저 부르고 예외를 잡음(param-peek)", arrayOf("reeh", "recorder", "param-peek"), false),
        Triple("REEH 상속 + Security 앞 Filter 가 getParameter 를 먼저 부르고 예외를 그대로 던짐(param-peek, exc.peek.rethrow=true)", arrayOf("reeh", "recorder", "param-peek"), true),
    )) {
        boot(*profiles, props = arrayOf("spring.servlet.multipart.max-file-size=1KB", "exc.peek.rethrow=$rethrow")).use { ctx ->
            println()
            println("### $label")
            schema(ctx)
            showHead()
            TraceFilter.events.clear()
            RecordingResolver.seen.clear()
            ParamPeekFilter.events.clear()
            show("POST /x1up/upload (4096 bytes)", upload("/x1up/upload", 4096))
            println("  dispatch: ${TraceFilter.events}")
            println("  resolver saw: ${RecordingResolver.seen}")
            if (ParamPeekFilter.events.isNotEmpty()) println("  param-peek: ${ParamPeekFilter.events}")
            show("POST /x1up/upload (100 bytes, 대조군)", upload("/x1up/upload", 100))
        }
    }
    RecordingResolver.withDispatch = false
}

// ---------------------------------------------------------------- [X8IDEM]

private fun idemGet(path: String, key: String): Pair<Resp, String> =
    RestClient.create(base()).get().uri(path).header("Idempotency-Key", key)
        .exchange { _, res ->
            Resp(res.statusCode.value(), res.headers.contentType?.toString() ?: "-",
                res.bodyTo(String::class.java) ?: "", null) to (res.headers.getFirst("Idempotent-Replayed") ?: "-")
        }!!

/**
 * [X8IDEM] afterCommit 에서 던지는 요청(commit 된 뒤 AdviceA 가 541)을 같은 Idempotency-Key 로 두 번 보낸다.
 * 멱등키 레이어(IdempotencyFilter)가 5xx 까지 저장할 때(Stripe 식)와 5xx 는 저장하지 않을 때, 응답과 행 수를 본다.
 * 대조군은 콜백에서 던지지 않는 /x8idem/ok(200, commit).
 */
internal fun runX8Idem() {
    envHeader("X8IDEM")
    println("IdempotencyFilter(order MIN+40, profile idem): Idempotency-Key 의 첫 응답(상태·바디)을 저장해 같은 키에 재생한다.")
    println("541 = AdviceA 가 받은 afterCommit 예외(commit 은 이미 끝났다). /x8idem/ok = 콜백에서 던지지 않는 대조군.")
    for (store5xx in listOf(true, false)) {
        boot("handlers", "idem", props = arrayOf("exc.idem.store-5xx=$store5xx")).use { ctx ->
            println()
            println("### exc.idem.store-5xx=$store5xx" + if (store5xx) " (5xx 까지 저장, Stripe 식)" else " (5xx 는 저장하지 않음)")
            schema(ctx)
            IdempotencyFilter.stored.clear()
            IdempotencyFilter.events.clear()
            println("| case | status | Idempotent-Replayed | body |")
            println("|---|---|---|---|")
            for ((label, path, key) in listOf(
                Triple("1st GET /x8/after-commit key=a1", "/x8/after-commit", "a1"),
                Triple("2nd GET /x8/after-commit key=a1 (retry)", "/x8/after-commit", "a1"),
                Triple("1st GET /x8idem/ok key=o1", "/x8idem/ok", "o1"),
                Triple("2nd GET /x8idem/ok key=o1 (retry)", "/x8idem/ok", "o1"),
            )) {
                val (r, replayed) = idemGet(path, key)
                println("| $label | ${r.status} | $replayed | ${r.body.replace("\n", " ").take(200)} |")
            }
            println("  rows(afterCommit) = ${call(GET, "/rows?tag=afterCommit").body}")
            println("  rows(idem-ok) = ${call(GET, "/rows?tag=idem-ok").body}")
            IdempotencyFilter.events.forEach { println("  idem: $it") }
        }
    }
}
