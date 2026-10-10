// SPRING-INTERNALS 2편 — ④ 스레드를 넘으면 SecurityContext가 비어 있다.
// [S8a] Boot 기본 applicationTaskExecutor에서 @Async가 @PreAuthorize 메서드를 부르면 무슨 예외가, 어떤 모양으로 오나.
// [S8b] DelegatingSecurityContextAsyncTaskExecutor 로 감싼 실행자에서는 같은 호출이 통과하나.
// [S8f] @Async 와 @PreAuthorize 를 한 메서드에 걸면 검사는 어느 스레드에서 도나(Future / void).
// [S8c] spring.threads.virtual.enabled=true(전파 없음)에서 S8a 반복.
// [S8g] (secLabExtra 태스크) io.micrometer:context-propagation + spring.task.execution.propagate-context=true.
// [S8e] MODE_INHERITABLETHREADLOCAL + core=1 풀: 먼저 만든 사용자의 context가 남는가.
// 실행: ./gradlew --offline -q secLab --args='S8' / 'S8c' / 'S8e',  ./gradlew -q secLabExtra --args='S8g'
package com.petercoders.blog.sec

import org.springframework.aop.framework.Advised
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** void @Async 에서 난 예외가 가는 곳을 센다. */
object AsyncUncaught {
    val count = AtomicInteger()
    @Volatile var lastClass: String? = null
    @Volatile var lastThread: String? = null
}

@Configuration(proxyBeanMethods = false)
@Profile("async")
@EnableAsync
class AsyncSecConfig {
    @Bean
    fun countingAsyncConfigurer(): AsyncConfigurer = object : AsyncConfigurer {
        override fun getAsyncUncaughtExceptionHandler() = AsyncUncaughtExceptionHandler { ex, _, _ ->
            AsyncUncaught.count.incrementAndGet()
            AsyncUncaught.lastClass = ex.javaClass.name
            AsyncUncaught.lastThread = Thread.currentThread().name
        }
    }
}

/** [S8b] Executor Bean 이 있으면 Boot 의 applicationTaskExecutor 는 물러난다(OnExecutorCondition). */
@Configuration(proxyBeanMethods = false)
@Profile("asyncdelegating")
class DelegatingExecutorConfig {
    @Bean("applicationTaskExecutor")
    fun applicationTaskExecutor(): DelegatingSecurityContextAsyncTaskExecutor {
        val pool = ThreadPoolTaskExecutor()
        pool.setThreadNamePrefix("deleg-")
        pool.setDaemon(true)
        pool.initialize()
        return DelegatingSecurityContextAsyncTaskExecutor(pool)
    }
}

/** [S8a] @Async 메서드가 다른 Bean 의 @PreAuthorize 메서드를 부른다. */
@Service
@Profile("async")
class AsyncCaller(private val secureProbe: SecureProbe) {
    @Async
    fun callSecured(tag: String): CompletableFuture<String> {
        secureProbe.secured(tag)
        return CompletableFuture.completedFuture("ok")
    }

    @Async
    fun whoAmI(): CompletableFuture<String> {
        val t = Thread.currentThread()
        val a = SecurityContextHolder.getContext().authentication
        return CompletableFuture.completedFuture("thread=${t.name} isVirtual=${t.isVirtual} authentication=${a?.name}")
    }
}

/** [S8f] @Async 와 @PreAuthorize 를 한 메서드에. 본문은 sec_log 에 한 행. */
@Service
@Profile("async")
class AsyncSecured(private val jdbc: JdbcTemplate) {
    @Async
    @PreAuthorize("hasRole('ADMIN')")
    fun securedAsync(tag: String): CompletableFuture<Unit> {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", tag)
        return CompletableFuture.completedFuture(Unit)
    }

    @Async
    @PreAuthorize("hasRole('ADMIN')")
    fun securedAsyncVoid(tag: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", tag)
    }
}

private fun describeFailure(f: CompletableFuture<*>): String {
    val sb = StringBuilder()
    try {
        f.get(5, TimeUnit.SECONDS)
        sb.append("get() -> ok")
    } catch (e: ExecutionException) {
        val c = e.cause
        sb.append("get() threw ${e.javaClass.simpleName}; getCause()=${c?.javaClass?.name}")
        sb.append("; cause is AccessDeniedException=${c is AccessDeniedException}; cause is AuthenticationException=${c is AuthenticationException}")
        sb.append("; cause.message=\"${c?.message}\"")
    }
    try {
        f.join()
    } catch (e: Throwable) {
        sb.append("\n    join() threw ${e.javaClass.simpleName}; getCause()=${e.cause?.javaClass?.simpleName}")
    }
    return sb.toString()
}

private fun waitRows(jdbc: JdbcTemplate, tag: String, ms: Long = 1500): Int {
    val end = System.currentTimeMillis() + ms
    var r = secRows(jdbc, tag)
    while (r == 0 && System.currentTimeMillis() < end) { Thread.sleep(50); r = secRows(jdbc, tag) }
    return r
}

/** 한 컨텍스트에서 S8a(+whoAmI)·S8f 를 차례로 잰다. */
fun s8Body(ctx: ConfigurableApplicationContext, label: String) {
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    ensureSecLog(jdbc)
    println("[$label] applicationTaskExecutor = ${ctx.getBean("applicationTaskExecutor").javaClass.name}")
    println("[$label] contextPropagatingTaskDecorator bean present = ${ctx.containsBean("contextPropagatingTaskDecorator")}")
    authAs("alice", "ROLE_ADMIN")
    println("[$label] caller thread=${Thread.currentThread().name} authentication=${SecurityContextHolder.getContext().authentication?.name}")
    val caller = ctx.getBean(AsyncCaller::class.java)
    println("[$label] whoAmI -> ${caller.whoAmI().get(5, TimeUnit.SECONDS)}")

    // S8a: @Async -> 다른 Bean 의 @PreAuthorize
    val fa = caller.callSecured("$label-a")
    println("[$label] S8a callSecured: ${describeFailure(fa)}")
    println("[$label] S8a rows=${waitRows(jdbc, "$label-a", 300)}")

    // S8f: @Async + @PreAuthorize 한 메서드
    val s = ctx.getBean(AsyncSecured::class.java)
    val chain = (s as Advised).advisors
    println("[$label] S8f AsyncSecured chain[0]=${chain[0].javaClass.simpleName}, chain=${chain.map { it.javaClass.simpleName }}")
    val ff = s.securedAsync("$label-f")
    println("[$label] S8f securedAsync(Future): ${describeFailure(ff)}")
    println("[$label] S8f securedAsync rows=${waitRows(jdbc, "$label-f", 300)}")
    AsyncUncaught.count.set(0); AsyncUncaught.lastClass = null; AsyncUncaught.lastThread = null
    val voidThrown = thrownOf { s.securedAsyncVoid("$label-v") }
    val vr = waitRows(jdbc, "$label-v", 1000)
    println("[$label] S8f securedAsyncVoid: caller saw thrown=$voidThrown; rows=$vr; AsyncUncaughtExceptionHandler count=${AsyncUncaught.count.get()} class=${AsyncUncaught.lastClass} thread=${AsyncUncaught.lastThread}")
}

fun runS8() {
    println("## [S8] @Async 와 SecurityContext — (a)(f) Boot 기본 실행자, (b) DelegatingSecurityContextAsyncTaskExecutor")
    println()
    val c1 = bootSec("seclab-s8-default", "async")
    try { s8Body(c1, "default") } finally { c1.close() }
    println()
    val c2 = bootSec("seclab-s8-deleg", "async,asyncdelegating")
    try { s8Body(c2, "delegating") } finally { c2.close() }
}

fun runS8c() {
    println("## [S8c] spring.threads.virtual.enabled=true, 전파 설정 없음 — S8a/S8f 반복")
    val c = bootSec("seclab-s8c", "async", "spring.threads.virtual.enabled=true")
    try { s8Body(c, "virtual") } finally { c.close() }
}

/** [S8g] secLabExtra 태스크(io.micrometer:context-propagation 포함)에서만 의미가 있다. */
fun runS8g() {
    val present = try { Class.forName("io.micrometer.context.ContextSnapshot"); true } catch (e: ClassNotFoundException) { false }
    println("## [S8g] context-propagation on classpath = $present")
    println()
    val c0 = bootSec("seclab-s8g-ctrl", "async")
    try { s8Body(c0, "lib-only(propagate-context unset)") } finally { c0.close() }
    println()
    val c1 = bootSec("seclab-s8g-platform", "async", "spring.task.execution.propagate-context=true")
    try { s8Body(c1, "propagate-context platform") } finally { c1.close() }
    println()
    val c2 = bootSec("seclab-s8g-virtual", "async", "spring.task.execution.propagate-context=true", "spring.threads.virtual.enabled=true")
    try { s8Body(c2, "propagate-context virtual") } finally { c2.close() }
}

/** [S8e] 별도 JVM 에서만 돌린다(전략을 전역으로 바꾼다). 요청 스레드 둘이 각자 새 SecurityContext 를 갖고 같은 풀에 일을 넣는다. */
fun runS8e() {
    println("## [S8e] SecurityContextHolder MODE_INHERITABLETHREADLOCAL + ThreadPoolTaskExecutor(core=1, max=1)")
    SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL)
    println("strategy = ${SecurityContextHolder.getContextHolderStrategy().javaClass.simpleName}")
    val pool = ThreadPoolTaskExecutor()
    pool.corePoolSize = 1; pool.maxPoolSize = 1; pool.setThreadNamePrefix("inh-"); pool.setDaemon(true)
    pool.initialize()
    fun request(user: String): String {
        var seen = ""
        val t = Thread({
            authAs(user, "ROLE_USER") // 요청마다 새 SecurityContext 객체
            val f = pool.submit<String> {
                "worker=${Thread.currentThread().name} sees authentication=${SecurityContextHolder.getContext().authentication?.name}"
            }
            seen = "request-thread user=$user -> ${f.get(5, TimeUnit.SECONDS)}"
        }, "req-$user")
        t.start(); t.join()
        return seen
    }
    println(request("alice"))
    println(request("bob"))
    pool.shutdown()
}
