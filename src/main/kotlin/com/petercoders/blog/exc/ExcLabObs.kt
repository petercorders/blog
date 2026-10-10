// SPRING-INTERNALS 3편 [X14OBS] — actuator(메트릭)·micrometer-tracing(brave) 가 클래스패스에 있을 때.
// 의존성은 excLabObs configuration 에만 있다. ./gradlew --offline -q excLabObs --args='X14OBS'
package com.petercoders.blog.exc

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

class X14ObsException(message: String) : RuntimeException(message)

/** Controller 안에서 본 MDC traceId 를 예외 메시지에 실어 던진다. */
@Profile("obs")
@RestController
class X14ObsController {
    @GetMapping("/x14/ise")
    fun ise(): Nothing = throw X14ObsException("controller-mdc-traceId=${MDC.get("traceId")}")
}

/** advice 안에서 본 MDC traceId 를 바디에 싣는다. AdviceA(@Order(1)) 보다 앞. X14ObsException 만 받는다. */
@Profile("obs")
@Order(0)
@RestControllerAdvice
class ObsAdvice {
    @ExceptionHandler(X14ObsException::class)
    fun ise(e: X14ObsException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(543).body(mapOf(
            "who" to "ObsAdvice#X14ObsException",
            "controller" to e.message,
            "advice-mdc-traceId" to MDC.get("traceId"),
        ))
}

/** 클래스가 들어 있는 jar 파일 이름(버전 포함)을 찍는다. excLabObs classpath 에만 있는 의존이라 리플렉션으로 찾는다. */
private fun jarOf(className: String): String = runCatching {
    val c = Class.forName(className)
    val ver = c.`package`?.implementationVersion
    val jar = c.protectionDomain?.codeSource?.location?.path?.substringAfterLast('/')
    "${jar ?: "?"}(Implementation-Version=${ver ?: "-"})"
}.getOrElse { "없음" }

internal fun runX14Obs() {
    envHeader("X14OBS")
    println("env: micrometer-tracing=${jarOf("io.micrometer.tracing.Tracer")} " +
        "micrometer-tracing-bridge-brave=${jarOf("io.micrometer.tracing.brave.bridge.BraveTracer")} " +
        "brave=${jarOf("brave.Tracing")}")
    boot("handlers", "obs", props = arrayOf(
        "management.tracing.sampling.probability=1.0",
    )).use { ctx ->
        schema(ctx)
        val reg = ctx.getBean(MeterRegistry::class.java)
        println("MeterRegistry = ${reg.javaClass.name}")
        println()
        println("### (1) http.server.requests 태그")
        for ((label, m, p) in listOf(
            Triple("advice 처리 541 GET /x2/ise", GET, "/x2/ise"),
            Triple("DHER 처리 400 GET /x4/param", GET, "/x4/param"),
            Triple("Filter 500 GET /x5/filter-boom", GET, "/x5/filter-boom"),
        )) {
            val r = call(m, p)
            println("- $label -> ${r.status}")
        }
        Thread.sleep(300)
        reg.find("http.server.requests").timers().sortedBy { it.id.getTag("uri") }.forEach { t ->
            println("  timer count=${t.count()} tags=${t.id.tags.joinToString { "${it.key}=${it.value}" }}")
        }
        println()
        println("### (2) traceId 가 Controller 와 @ExceptionHandler 안에서 같은가(MDC)")
        val r = call(GET, "/x14/ise")
        println("  GET /x14/ise -> ${r.status} ${r.body}")
    }
}
