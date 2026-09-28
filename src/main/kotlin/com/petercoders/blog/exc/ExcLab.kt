// SPRING-INTERNALS 3편(예외 처리는 한 곳이 아니라 경계마다 다르다) — 실험 러너.
// 마커 → 로그 파일: [X1]→x1.log · [X2]→x2.log · [X3]→x3.log · [X4]→x4.log · [X5]→x5.log
//                   [X6]→x6.log · [X7]→x7.log · [X8]→x8.log · [X9]→x9.log
//
// 실행: cd ~/Desktop/development/blog
//       ./gradlew -q excLab                 # 최초 1회는 web 스타터를 받아야 해서 --offline 을 못 쓴다
//       ./gradlew --offline -q excLab --args='X2'
//
// 판정은 시간이 아니라 결과다. 실제 내장 톰캣을 띄우고 RestClient 로 때려
// 상태 코드와 응답 바디를 그대로 찍는다. 로그 문자열은 증거로 쓰지 않는다.
package com.petercoders.blog.exc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.web.client.RestClient
import org.springframework.web.method.ControllerAdviceBean
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver
import kotlin.system.exitProcess

const val PORT = 18099

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.exc"])
@EnableAsync
class ExcLabApp

private fun boot(vararg profiles: String, props: Array<String> = emptyArray()): ConfigurableApplicationContext =
    SpringApplicationBuilder(ExcLabApp::class.java)
        .profiles(*profiles)
        .properties(
            "spring.main.banner-mode=off",
            "server.port=$PORT",
            "server.error.include-message=always",
            "spring.datasource.url=jdbc:h2:mem:exclab;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "logging.level.root=WARN",
            *props,
        )
        .run()

private fun schema(ctx: ConfigurableApplicationContext) {
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    jdbc.execute("drop table if exists exc_log")
    jdbc.execute("create table exc_log(id identity primary key, tag varchar(40))")
}

private data class Res(val status: Int, val contentType: String, val body: String)

private fun get(path: String): Res =
    RestClient.create("http://localhost:$PORT").get().uri(path).exchange { _, res ->
        Res(
            res.statusCode.value(),
            res.headers.contentType?.toString() ?: "-",
            res.bodyTo(String::class.java)?.replace("\n", " ")?.take(400) ?: "",
        )
    }!!

private fun post(path: String): Res =
    RestClient.create("http://localhost:$PORT").post().uri(path).exchange { _, res ->
        Res(res.statusCode.value(), res.headers.contentType?.toString() ?: "-",
            res.bodyTo(String::class.java)?.replace("\n", " ")?.take(400) ?: "")
    }!!

private fun row(case: String, r: Res) = "| %-22s | %3d | %-26s | %s |".format(case, r.status, r.contentType, r.body)
private fun head() { println("| case | status | content-type | body |"); println("|---|---|---|---|") }

// ------------------------------------------------------------------ [X1]
private fun runX1() {
    println("## [X1] 등록된 리졸버·advice·필터 실물")
    boot("handlers", "reeh").use { ctx ->
        val composite = ctx.getBean("handlerExceptionResolver")
        println("handlerExceptionResolver = ${composite.javaClass.name}")
        if (composite is HandlerExceptionResolverComposite) {
            composite.exceptionResolvers?.forEachIndexed { i, r -> println("  [$i] ${r.javaClass.name}") }
        }
        // ExceptionHandlerExceptionResolver 는 composite 안에서만 만들어지고 별도 빈으로 등록되지 않는다
        // (getBean(ExceptionHandlerExceptionResolver::class.java) 는 NoSuchBeanDefinitionException).
        // 그래서 composite 리스트에서 직접 꺼낸다.
        val ehr = composite.let { c -> (c as HandlerExceptionResolverComposite).exceptionResolvers
            ?.filterIsInstance<ExceptionHandlerExceptionResolver>()?.firstOrNull() }
        println("ExceptionHandlerExceptionResolver (composite[0]) = ${ehr?.javaClass?.name}, order = ${ehr?.order}")
        println()
        println("| advice bean | type | order |")
        println("|---|---|---|")
        ControllerAdviceBean.findAnnotatedBeans(ctx).forEach {
            println("| ${it.beanType?.simpleName} | ${it.beanType?.name} | ${it.order} |")
        }
        println()
        println("| filter registration | order | class |")
        println("|---|---|---|")
        ctx.getBeansOfType(FilterRegistrationBean::class.java)
            .entries.sortedBy { it.value.order }
            .forEach { (n, f) -> println("| $n | ${f.order} | ${f.filter?.javaClass?.name} |") }
    }
}

// ------------------------------------------------------------------ [X2]
private fun runX2() {
    println("## [X2] 컨트롤러 로컬 · @Order(1) advice · @Order(2) advice 중 누가 잡나")
    println("상태 코드가 곧 승자다. 521=컨트롤러 로컬 / 541=AdviceA#RuntimeException")
    println("                     542=AdviceA#IllegalArgumentException / 561=AdviceB#IllegalStateException")
    boot("handlers").use {
        schema(it)
        head()
        println(row("local ISE", get("/x2/local-ise")))
        println(row("advice ISE", get("/x2/ise")))
        println(row("advice IAE", get("/x2/iae")))
        println(row("advice NPE", get("/x2/npe")))
    }
}

// ------------------------------------------------------------------ [X3]
private fun runX3() {
    println("## [X3] advice 가 하나도 없을 때의 기본 응답과 spring.mvc.problemdetails.enabled")
    listOf(emptyArray<String>(), arrayOf("spring.mvc.problemdetails.enabled=true")).forEach { extra ->
        boot(props = extra).use { ctx ->
            val label = if (extra.isEmpty()) "default" else "problemdetails=true"
            println()
            println("### $label")
            println("resolved spring.mvc.problemdetails.enabled = " +
                ctx.environment.getProperty("spring.mvc.problemdetails.enabled"))
            println("beans matching 'roblemDetails' = " +
                ctx.beanDefinitionNames.filter { it.contains("roblemDetails") })
            schema(ctx)
            head()
            println(row("$label ISE", get("/x2/ise")))
            println(row("$label missing param", get("/x4/param")))
        }
    }
}

// ------------------------------------------------------------------ [X4]
private fun runX4() {
    println("## [X4] ResponseEntityExceptionHandler 를 상속만 했을 때 자동으로 덮이는 것")
    val handled = org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler::class.java
        .declaredMethods.filter { it.name.startsWith("handle") }.map { it.name }.sorted().distinct()
    println("declared handle* methods (${handled.size}): $handled")
    boot("reeh").use {
        schema(it)
        head()
        println(row("missing param", get("/x4/param")))
        println(row("method not allowed", post("/x2/ise")))
        println(row("no such path", get("/x4/nope")))
    }
}

// ------------------------------------------------------------------ [X5][X6]
private fun runX5X6() {
    println("## [X5] 필터에서 던진 예외 — advice 가 붙어 있어도 advice 응답이 나오는가")
    boot("handlers").use {
        schema(it)
        TraceFilter.events.clear()
        head()
        println(row("filter boom", get("/x5/filter-boom")))
        println(row("controller ISE", get("/x2/ise")))
        println()
        println("## [X6] 그 요청을 실제로 받은 자리 — 디스패치 기록")
        TraceFilter.events.forEachIndexed { i, e -> println("  [$i] $e") }
    }
}

// ------------------------------------------------------------------ [X7]
private fun runX7() {
    println("## [X7] @Async 예외의 행선지")
    boot("handlers").use {
        schema(it)
        head()
        println(row("async void", get("/x7/async-void")))
        println(row("future not joined", get("/x7/async-future")))
        println(row("future joined", get("/x7/async-join")))
        Thread.sleep(300)
    }
    println()
    println("## [X7b] AsyncUncaughtExceptionHandler 를 커스텀으로 갈아끼웠을 때")
    boot("handlers", "asynch").use {
        schema(it)
        head()
        println(row("async void", get("/x7/async-void")))
        Thread.sleep(300)
        println("CountingAsyncConfig.caught = ${CountingAsyncConfig.caught.get()}, last = ${CountingAsyncConfig.last}")
    }
}

// ------------------------------------------------------------------ [X8]
private fun runX8() {
    println("## [X8] 트랜잭션 동기화 콜백 네 단계에서 던진 예외의 행선지")
    boot("handlers").use {
        schema(it)
        head()
        listOf("plain" to "/x8/plain",
               "beforeCommit" to "/x8/before-commit",
               "beforeCompletion" to "/x8/before-completion",
               "afterCommit" to "/x8/after-commit",
               "afterCompletion" to "/x8/after-completion").forEach { (name, path) ->
            println(row(name, get(path)))
        }
        println()
        println("| tag | rows committed |")
        println("|---|---|")
        listOf("plain", "beforeCommit", "beforeCompletion", "afterCommit", "afterCompletion").forEach { t ->
            println("| $t | ${get("/rows?tag=$t").body} |")
        }
    }
}

// ------------------------------------------------------------------ [X9]
private fun runX9() {
    println("## [X9] 경계별 종합 — 누가 받았나 / 상태 코드 / 바디")
    boot("handlers").use {
        schema(it)
        TraceFilter.events.clear()
        head()
        println(row("controller", get("/x2/ise")))
        println(row("filter", get("/x5/filter-boom")))
        println(row("async void", get("/x7/async-void")))
        println(row("commit(beforeCommit)", get("/x8/before-commit")))
        println(row("sync(afterCompletion)", get("/x8/after-completion")))
        println()
        println("dispatch trace:")
        TraceFilter.events.forEachIndexed { i, e -> println("  [$i] $e") }
    }
}

fun main(args: Array<String>) {
    val only = args.firstOrNull()?.uppercase()
    fun step(tag: String, f: () -> Unit) { if (only == null || only == tag) { f(); println() } }
    step("X1") { runX1() }
    step("X2") { runX2() }
    step("X3") { runX3() }
    step("X4") { runX4() }
    step("X5") { runX5X6() }
    step("X7") { runX7() }
    step("X8") { runX8() }
    step("X9") { runX9() }
    exitProcess(0)
}
