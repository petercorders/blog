// SPRING-INTERNALS 5편 — [A2eCtx] io.micrometer:context-propagation 이 클래스패스에 있을 때.
// 이 파일만 io.micrometer.context 를 참조한다. 의존은 build.gradle.kts 의 asyncLabCtx configuration 에만 있고
// (컴파일은 compileOnly), main runtimeClasspath 에는 없다. ./gradlew --offline -q asyncLabCtx --args='A2eCtx'
package com.petercoders.blog.async

import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Supplier
import io.micrometer.context.ContextRegistry
import org.slf4j.MDC

private fun accessorKeys() = ContextRegistry.getInstance().threadLocalAccessors.map { it.key() }

fun runA2eCtx() {
    val hasLib = runCatching { Class.forName("io.micrometer.context.ContextSnapshot") }.isSuccess
    println("## [A2e-d] io.micrometer:context-propagation 있음 · propagate-context 미설정")
    println("  클래스패스에 io.micrometer.context.ContextSnapshot = $hasLib")
    boot().use { ctx ->
        println("  containsBean(\"contextPropagatingTaskDecorator\") = ${ctx.containsBean("contextPropagatingTaskDecorator")}")
        val probe = ctx.getBean(CtxProbe::class.java)
        withCallerContext(ctx) { println("  applicationTaskExecutor: ${probe.snapDefault().get(10, TimeUnit.SECONDS)}") }
    }
    println("## [A2e-e] io.micrometer:context-propagation 있음 · spring.task.execution.propagate-context=true")
    println("  ContextRegistry 에 등록된 ThreadLocalAccessor key = ${accessorKeys()}")
    boot(props = arrayOf("spring.task.execution.propagate-context=true")).use { ctx ->
        println("  containsBean(\"contextPropagatingTaskDecorator\") = ${ctx.containsBean("contextPropagatingTaskDecorator")}" +
            (if (ctx.containsBean("contextPropagatingTaskDecorator")) " class=${ctx.getBean("contextPropagatingTaskDecorator").javaClass.name}" else ""))
        val probe = ctx.getBean(CtxProbe::class.java)
        withCallerContext(ctx) { println("  applicationTaskExecutor: ${probe.snapDefault().get(10, TimeUnit.SECONDS)}") }

        println("## [A2e-f] 같은 컨텍스트에서 MDC 'traceId' accessor 를 ContextRegistry 에 등록한 뒤")
        ContextRegistry.getInstance().registerThreadLocalAccessor<String>(
            "traceId",
            Supplier { MDC.get("traceId") },
            Consumer { MDC.put("traceId", it) },
            Runnable { MDC.remove("traceId") },
        )
        println("  ContextRegistry 에 등록된 ThreadLocalAccessor key = ${accessorKeys()}")
        withCallerContext(ctx) { println("  applicationTaskExecutor: ${probe.snapDefault().get(10, TimeUnit.SECONDS)}") }
    }
}
