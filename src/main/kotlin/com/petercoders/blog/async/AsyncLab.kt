// SPRING-INTERNALS 5편(@Async·@Scheduled) — 실험 러너.
// 마커 → 로그 파일(runs/, 대소문자 그대로): [A1]·[A1b]→A1.log · [A1c]→A1c.log · [A1d]→A1d.log · [A1e]→A1e.log
//   [A2]→A2.log · [A2c]→A2c.log · [A2d]→A2d.log · [A2e]·[A2eCtx]→A2e.log · [A2f]→A2f.log · [A2g]→A2g.log
//   [A3]→A3.log(2026-10-08 재실행은 A3-rerun.log) · [A3b]→A3b.log(재실행은 A3b-rerun.log) · [A3c]→A3c.log · [A3d]→A3d.log · [A3e]→A3e.log
//   [A4]→A4.log · [A4b]→A4b.log · [A4c]→A4c.log · [A4d]→A4d.log · [A4e]→A4e.log · [A4f]→A4f.log · [A4g]→A4g.log
//   [A5]→A5.log · [A6]→A6.log
//
// 실행: cd ~/Desktop/development/blog
//       ./gradlew --offline -q asyncLab --args='A1'
//       ./gradlew --offline -q asyncLab --args='A4g 1 2'     (조건 일부만)
//       ./gradlew --offline -q asyncLabCtx --args='A2eCtx'   (context-propagation 이 클래스패스에 있을 때)
//       ./gradlew --offline -q asyncLabObs --args='A6'       (actuator 가 클래스패스에 있을 때)
//       A4f 는 a4f_sigterm.py 가 java 를 직접 띄우고 SIGTERM 을 보낸다(README).
//
// 판정은 로그 유무가 아니라 결과다 — 등록된 빈의 실제 클래스, 잡힌 예외 객체의 클래스명,
// 카운터 값, H2 행 수, 스레드 이름. 절대 시간(ms)은 이 기계를 탄다.
// 모든 실행은 머리에 실제 런타임(Runtime.version()·Spring·Kotlin 버전)을 찍는다.
package com.petercoders.blog.async

import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.slf4j.MDC
import org.springframework.boot.SpringBootVersion
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.SpringVersion
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.TransactionTemplate
import kotlin.system.exitProcess

// @EnableScheduling 은 AsyncProbes2.kt 의 SchedulingOn(@Profile("!nosched"))으로 옮겼다.
@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.async"])
@EnableAsync
class AsyncLabApp

internal fun boot(vararg profiles: String, props: Array<String> = emptyArray()): ConfigurableApplicationContext =
    SpringApplicationBuilder(AsyncLabApp::class.java)
        .profiles(*profiles)
        .properties(
            "spring.main.banner-mode=off",
            "spring.main.web-application-type=none",
            "spring.datasource.url=${Db.URL}",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "logging.level.root=WARN",
            *props,
        )
        .run()

/** 셸의 java -version 이 아니라 이 JVM 이 실제로 무엇인지. */
private fun runtimeHeader() {
    println("# runtime: Runtime.version()=${Runtime.version()} java.vendor=${System.getProperty("java.vendor")} " +
        "java.home=${System.getProperty("java.home")}")
    println("# runtime: Spring Framework ${SpringVersion.getVersion()} / Spring Boot ${SpringBootVersion.getVersion()} " +
        "/ Kotlin ${KotlinVersion.CURRENT} / pid ${ProcessHandle.current().pid()}")
}

/** 등록된 실행자·스케줄러의 실물을 찍는다. 단언 전에 실물부터. */
private fun beanReport(ctx: ConfigurableApplicationContext) {
    val hasExec = ctx.beanFactory.containsBean("applicationTaskExecutor")
    println("applicationTaskExecutor 빈 존재 = $hasExec")
    if (hasExec) {
        when (val e = ctx.getBean("applicationTaskExecutor")) {
            is ThreadPoolTaskExecutor ->
                println("  class=${e.javaClass.name} core=${e.corePoolSize} max=${e.maxPoolSize} " +
                    "queueCapacity=${e.queueCapacity} keepAliveSec=${e.keepAliveSeconds}")
            else -> println("  class=${e.javaClass.name}")
        }
    }
    if (ctx.containsBean("taskScheduler")) {
        when (val s = ctx.getBean("taskScheduler")) {
            is ThreadPoolTaskScheduler -> println("taskScheduler class=${s.javaClass.name} poolSize=${s.poolSize}")
            is SimpleAsyncTaskScheduler -> println("taskScheduler class=${s.javaClass.name} (풀 크기 개념 없음)")
            else -> println("taskScheduler class=${s.javaClass.name}")
        }
    } else {
        println("taskScheduler 빈 없음")
    }
    println("Executor 타입 빈 = ${ctx.getBeanNamesForType(Executor::class.java).sorted()}")
    println("TaskExecutor 타입 빈 = ${ctx.getBeanNamesForType(TaskExecutor::class.java).sorted()}")
    println("TaskScheduler 타입 빈 = ${ctx.getBeanNamesForType(TaskScheduler::class.java).sorted()}")
    println("AsyncConfigurer 빈 = ${ctx.getBeanNamesForType(AsyncConfigurer::class.java).sorted()}")
    println("@Async 가 실제로 올라탄 스레드: " + ctx.getBean(AsyncProbe::class.java).whereAmI().get(10, TimeUnit.SECONDS))
}

// ------------------------------------------------------------------ [A1] [A1b] [A1c]
private fun runA1() {
    println("## [A1] 가상 스레드 off — 등록된 실행자·스케줄러 실물")
    boot().use(::beanReport)
}

private fun runA1b() {
    println("## [A1b] spring.threads.virtual.enabled=true — 같은 이름, 다른 클래스")
    boot(props = arrayOf("spring.threads.virtual.enabled=true")).use(::beanReport)
}

private fun runA1c() {
    println("## [A1c] 사용자가 Executor 빈을 등록했을 때")
    boot("custom").use(::beanReport)
    println("## [A1c-force] 같은 상황 + spring.task.execution.mode=force")
    boot("custom", props = arrayOf("spring.task.execution.mode=force")).use(::beanReport)
}

// ------------------------------------------------------------------ [A1d] 풀과 큐
private fun holdRun(
    label: String, n: Int, vararg props: String,
    profiles: Array<String> = emptyArray(),
    whileHeld: ((ConfigurableApplicationContext) -> Unit)? = null,
) {
    println("## $label")
    boot(*profiles, props = arrayOf(*props)).use { ctx ->
        val p = ctx.getBean(AsyncProbe::class.java)
        val ex = ctx.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor::class.java)
        println("  설정: core=${ex.corePoolSize} max=${ex.maxPoolSize} queueCapacity=${ex.queueCapacity}")
        Hold.reset()
        val accepted = mutableListOf<CompletableFuture<String>>()
        val rejected = linkedMapOf<String, Int>()
        var firstMessage: String? = null
        repeat(n) {
            try {
                accepted += p.hold()
            } catch (e: Throwable) {
                rejected.merge(e.javaClass.name, 1, Int::plus)
                if (firstMessage == null) firstMessage = e.message
            }
        }
        Thread.sleep(300)
        println("  제출 ${n}건 → 300ms 뒤 poolSize=${ex.poolSize} activeCount=${ex.activeCount} queueSize=${ex.queueSize}")
        println("  호출자가 받은 것: future ${accepted.size}건, 예외 ${if (rejected.isEmpty()) "없음" else rejected.toString()}")
        firstMessage?.let { println("  첫 예외 메시지: ${it.take(220)}") }
        whileHeld?.invoke(ctx)
        Hold.release()
        val names = accepted.map { it.get(20, TimeUnit.SECONDS) }.toSet()
        println("  latch 해제 후 완료 ${accepted.size}건, 쓰인 스레드 ${names.size}개: ${names.sorted()}")
    }
}

private fun runA1d(sel: Set<String>) {
    if (sel.isEmpty() || "1" in sel) holdRun("[A1d-1] 기본값 · 20건", 20)
    if (sel.isEmpty() || "2" in sel) holdRun("[A1d-2] queue-capacity=4 · max-size=16 · 20건", 20,
        "spring.task.execution.pool.queue-capacity=4", "spring.task.execution.pool.max-size=16")
    if (sel.isEmpty() || "3" in sel) holdRun("[A1d-3] queue-capacity=4 · max-size=16 · 30건", 30,
        "spring.task.execution.pool.queue-capacity=4", "spring.task.execution.pool.max-size=16")
    if (sel.isEmpty() || "4" in sel) holdRun("[A1d-4] queue-capacity=0 · max-size=16 · 20건 (SynchronousQueue)", 20,
        "spring.task.execution.pool.queue-capacity=0", "spring.task.execution.pool.max-size=16")
    // Boot 4.1.1 에는 거절 정책 프로퍼티가 없다. ThreadPoolTaskExecutorCustomizer 로 건다(AsyncProbes2.kt CallerRunsConfig).
    if (sel.isEmpty() || "5" in sel) holdRun("[A1d-5] queue-capacity=4 · max-size=16 · 20건으로 포화시킨 뒤 whereAmI() — CallerRunsPolicy(Customizer)", 20,
        "spring.task.execution.pool.queue-capacity=4", "spring.task.execution.pool.max-size=16",
        profiles = arrayOf("callerruns")) { ctx ->
        val ex = ctx.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor::class.java)
        println("  rejectedExecutionHandler = ${ex.threadPoolExecutor.rejectedExecutionHandler.javaClass.name}")
        println("  포화 상태에서 whereAmI() → ${ctx.getBean(AsyncProbe::class.java).whereAmI().get(10, TimeUnit.SECONDS)}")
    }
}

// ------------------------------------------------------------------ [A1e] Executor Bean 매트릭스
// @EnableAsync 의 advice 는 AnnotationAsyncExecutionInterceptor(org.springframework.scheduling.annotation)라
// logger 이름이 그 패키지다(LogFactory.getLog(getClass())). aop.interceptor 도 같이 연다.
private val asyncInfo = arrayOf("logging.level.org.springframework.aop.interceptor=INFO",
    "logging.level.org.springframework.scheduling.annotation=INFO")

private fun matrixCase(label: String, profiles: Array<String>, props: Array<String> = emptyArray(), extra: (ConfigurableApplicationContext) -> Unit = {}) {
    println("## $label  (profiles=${profiles.toList()} props=${props.toList()})")
    boot(*profiles, props = asyncInfo + props).use { ctx ->
        beanReport(ctx)
        extra(ctx)
    }
}

private fun runA1e(sel: Set<String>) {
    fun on(id: String) = sel.isEmpty() || id in sel
    if (on("i")) matrixCase("[A1e-i] ExecutorService Bean(myOwnExecutor) + @EnableScheduling (= A1c)", arrayOf("custom"))
    if (on("ii")) matrixCase("[A1e-ii] ExecutorService Bean, @EnableScheduling 없음", arrayOf("custom", "nosched"))
    if (on("iii")) matrixCase("[A1e-iii] ThreadPoolTaskExecutor Bean 'myPool'(my-) + @EnableScheduling", arrayOf("e-pool"))
    if (on("iv")) matrixCase("[A1e-iv] ThreadPoolTaskExecutor Bean 'myPool'(my-), @EnableScheduling 없음", arrayOf("e-pool", "nosched"))
    if (on("v")) matrixCase("[A1e-v] ThreadPoolTaskExecutor Bean 이름 'taskExecutor'(te-) + @EnableScheduling", arrayOf("e-te"))
    if (on("vi")) matrixCase("[A1e-vi] @Bean(defaultCandidate=false) reportExecutor(Boot builder, report-) + @EnableScheduling",
        arrayOf("e-report")) { ctx ->
        println("@Async(\"reportExecutor\") 가 올라탄 스레드: " +
            ctx.getBean(AsyncProbe::class.java).whereAmIReport().get(10, TimeUnit.SECONDS))
    }
    if (on("vii")) matrixCase("[A1e-vii] ExecutorService Bean + spring.task.execution.mode=force (= A1c-force)", arrayOf("custom"),
        arrayOf("spring.task.execution.mode=force"))
    if (on("viii")) matrixCase("[A1e-viii] ThreadPoolTaskExecutor @Primary 'myPool'(my-) + @EnableScheduling", arrayOf("e-primary"))
}

// ------------------------------------------------------------------ [A2] [A2c] [A2d]
private fun runA2() {
    println("## [A2] 반환 타입과 기다리는 방법이 만드는 예외 객체")
    boot().use { ctx ->
        val p = ctx.getBean(AsyncProbe::class.java)

        val f1 = p.failCompletable()
        try { f1.get(10, TimeUnit.SECONDS) } catch (e: Throwable) {
            println("CompletableFuture.get()  caught=${e.javaClass.name} cause=${e.cause?.javaClass?.name}")
        }

        val f2 = p.failCompletable()
        try { f2.join() } catch (e: Throwable) {
            println("CompletableFuture.join() caught=${e.javaClass.name} cause=${e.cause?.javaClass?.name}")
        }

        val f3 = p.failFuture()
        try { f3.get(10, TimeUnit.SECONDS) } catch (e: ExecutionException) {
            println("Future.get()             caught=${e.javaClass.name} cause=${e.cause?.javaClass?.name}")
        }

        val f4 = p.failCompletable()
        Thread.sleep(500)
        println("기다리지 않은 future: isCompletedExceptionally=${f4.isCompletedExceptionally} isDone=${f4.isDone}")

        p.failUnit()
        Thread.sleep(500)
        println("kotlin.Unit 반환: 호출자가 받은 객체 = null (핸들러로 갔다)")
    }
}

private fun runA2c() {
    println("## [A2c] get(timeout) 이 만드는 세 번째 예외")
    boot().use { ctx ->
        val f = ctx.getBean(AsyncProbe::class.java).sleepThen(3)
        println("반환된 future 클래스 = ${f.javaClass.name}")
        try { f.get(1, TimeUnit.SECONDS) } catch (e: TimeoutException) {
            println("get(1,SECONDS) caught=${e.javaClass.name} cause=${e.cause?.javaClass?.name}")
        }
        println("타임아웃 이후 future 상태: isDone=${f.isDone} isCancelled=${f.isCancelled}")
    }
}

private fun runA2d() {
    println("## [A2d] get(1s) 타임아웃 뒤 cancel(true) — 반환 타입별로 작업이 멈추는가 (본문 3초)")
    boot().use { ctx ->
        val p = ctx.getBean(AsyncProbe::class.java)

        println("-- CompletableFuture 반환")
        val cf = p.sleepCountingCf(3)
        println("반환된 future 클래스 = ${cf.javaClass.name}")
        try { cf.get(1, TimeUnit.SECONDS) } catch (e: TimeoutException) { println("get(1s) caught=${e.javaClass.name}") }
        println("cancel(true) 반환=${cf.cancel(true)} isCancelled=${cf.isCancelled} isDone=${cf.isDone}")
        Thread.sleep(4000)
        println("4초 뒤 작업 카운터: ${A2dTally.cf}")

        println("-- Future 반환")
        val fu = p.sleepCountingFuture(3)
        println("반환된 future 클래스 = ${fu.javaClass.name}")
        try { fu.get(1, TimeUnit.SECONDS) } catch (e: TimeoutException) { println("get(1s) caught=${e.javaClass.name}") }
        println("cancel(true) 반환=${fu.cancel(true)} isCancelled=${fu.isCancelled} isDone=${fu.isDone}")
        Thread.sleep(4000)
        println("4초 뒤 작업 카운터: ${A2dTally.future}")

        println("-- CompletableFuture.orTimeout(1s)")
        val ot = p.sleepCountingOrTimeout(3).orTimeout(1, TimeUnit.SECONDS)
        try { ot.join() } catch (e: Throwable) { println("join() caught=${e.javaClass.name} cause=${e.cause?.javaClass?.name}") }
        println("isCompletedExceptionally=${ot.isCompletedExceptionally}")
        Thread.sleep(4000)
        println("4초 뒤 작업 카운터: ${A2dTally.orTimeout}")
    }
}

// ------------------------------------------------------------------ [A2e] 컨텍스트 전파
internal fun withCallerContext(ctx: ConfigurableApplicationContext, block: () -> Unit) {
    MDC.put("traceId", "t-42")
    SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken("kim", "n/a", emptyList())
    try {
        TransactionTemplate(ctx.getBean(org.springframework.transaction.PlatformTransactionManager::class.java)).executeWithoutResult {
            println("  호출 스레드      : ${ctxSnapshot()}")
            block()
        }
    } finally {
        MDC.clear(); SecurityContextHolder.clearContext()
    }
}

private fun runA2e(sel: Set<String>) {
    fun on(id: String) = sel.isEmpty() || id in sel
    if (on("a")) {
        println("## [A2e-a] 기본값 — MDC·SecurityContext·Transaction 이 @Async 로 넘어가는가")
        boot().use { ctx ->
            val probe = ctx.getBean(CtxProbe::class.java)
            withCallerContext(ctx) { println("  applicationTaskExecutor: ${probe.snapDefault().get(10, TimeUnit.SECONDS)}") }
        }
    }
    if (on("b")) {
        println("## [A2e-b] MDC 복사 TaskDecorator Bean 등록 — 어느 실행자에 붙는가")
        boot("mdc", "pools").use { ctx ->
            val probe = ctx.getBean(CtxProbe::class.java)
            val plain = ctx.getBean("plainPool", ThreadPoolTaskExecutor::class.java)
            val builder = ctx.getBean("builderPool", ThreadPoolTaskExecutor::class.java)
            println("  plainPool core=${plain.corePoolSize} / builderPool core=${builder.corePoolSize}")
            println("  applicationTaskExecutor 빈 존재 = ${ctx.containsBean("applicationTaskExecutor")}")
            withCallerContext(ctx) {
                println("  applicationTaskExecutor: ${probe.snapDefault().get(10, TimeUnit.SECONDS)}")
                println("  plainPool(new)         : ${probe.snapPlain().get(10, TimeUnit.SECONDS)}")
                println("  builderPool(builder)   : ${probe.snapBuilder().get(10, TimeUnit.SECONDS)}")
            }
        }
    }
    if (on("c")) {
        println("## [A2e-c] spring.task.execution.propagate-context=true, io.micrometer:context-propagation 없음")
        val hasLib = runCatching { Class.forName("io.micrometer.context.ContextSnapshot") }.isSuccess
        println("  클래스패스에 io.micrometer.context.ContextSnapshot = $hasLib")
        boot(props = arrayOf("spring.task.execution.propagate-context=true")).use { ctx ->
            println("  containsBean(\"contextPropagatingTaskDecorator\") = ${ctx.containsBean("contextPropagatingTaskDecorator")}")
            val probe = ctx.getBean(CtxProbe::class.java)
            withCallerContext(ctx) { println("  applicationTaskExecutor: ${probe.snapDefault().get(10, TimeUnit.SECONDS)}") }
        }
    }
}

// ------------------------------------------------------------------ [A2f] 같은 풀 안에서 결과를 기다리기
private fun runA2f() {
    println("## [A2f] core-size=2 · outer @Async 2건이 같은 실행자의 inner @Async 를 get(3s) 로 기다린다")
    boot("nested", props = arrayOf("spring.task.execution.pool.core-size=2")).use { ctx ->
        val outer = ctx.getBean(NestedOuter::class.java)
        val t0 = System.currentTimeMillis()
        val same = (1..2).map { outer.outer(false) }
        same.forEach { println("  같은 실행자: ${it.get(10, TimeUnit.SECONDS)}") }
        println("  두 outer 가 끝난 시각 t=${System.currentTimeMillis() - t0}ms")
        println("## [A2f-b] inner 를 @Async(\"innerExecutor\") 로 나눈 같은 구조")
        val t1 = System.currentTimeMillis()
        val sep = (1..2).map { outer.outer(true) }
        sep.forEach { println("  나눈 실행자: ${it.get(10, TimeUnit.SECONDS)}") }
        println("  두 outer 가 끝난 시각 t=${System.currentTimeMillis() - t1}ms")
    }
}

// ------------------------------------------------------------------ [A2g] Transaction 과 @Async, AFTER_COMMIT
private fun runA2g() {
    println("## [A2g] Transaction 안에서 부른 @Async 와 @TransactionalEventListener(AFTER_COMMIT)")
    Db.resetOrders()
    boot("txev").use { ctx ->
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        val tx = TransactionTemplate(ctx.getBean(org.springframework.transaction.PlatformTransactionManager::class.java))
        val probe = ctx.getBean(TxEvProbe::class.java)
        val events: ApplicationEventPublisher = ctx   // 컨텍스트 자신이 publisher 다(Bean 으로 조회되지 않는다)

        println("-- (1) insert → @Async countRowsLater() → publish → 1초 sleep → commit")
        A2gLog.lines.clear()
        var counted: CompletableFuture<String>? = null
        tx.executeWithoutResult {
            jdbc.update("insert into orders(tag) values ('o1')")
            counted = probe.countRowsLater()
            events.publishEvent(OrderPlaced("commit"))
            Thread.sleep(1000)
            println("  commit 직전 호출 스레드가 보는 행 수(같은 Transaction) = ${jdbc.queryForObject("select count(*) from orders", Int::class.java)}")
        }
        println("  @Async countRowsLater() 결과 = ${counted!!.get(10, TimeUnit.SECONDS)}")
        Thread.sleep(500)
        A2gLog.lines.forEach { println("  $it") }
        println("  커밋 뒤 행 수 = ${Db.countOrders()}")

        println("-- (2) 같은 흐름을 setRollbackOnly 로 롤백")
        A2gLog.lines.clear(); A2gLog.sideEffects.set(0)
        tx.executeWithoutResult { status ->
            jdbc.update("insert into orders(tag) values ('o2')")
            probe.sideEffect("rollback")
            events.publishEvent(OrderPlaced("rollback"))
            Thread.sleep(300)
            status.setRollbackOnly()
        }
        Thread.sleep(500)
        println("  @Async 부수효과 실행 횟수 = ${A2gLog.sideEffects.get()}")
        A2gLog.lines.forEach { println("  $it") }
        println("  리스너 실행 줄 수 = ${A2gLog.lines.count { it.contains("리스너") }}")
        println("  롤백 뒤 행 수 = ${Db.countOrders()} (1 이면 o2 는 남지 않았다)")

        println("-- (3) Transaction 밖에서 publish")
        A2gLog.lines.clear()
        events.publishEvent(OrderPlaced("no-tx"))
        Thread.sleep(500)
        if (A2gLog.lines.isEmpty()) println("  (리스너 실행 없음)")
        A2gLog.lines.forEach { println("  $it") }
    }
}

// ------------------------------------------------------------------ [A3] [A3b] [A3c] [A3d] [A3e]
/**
 * afterCloseMs: close() 반환 뒤에도 같은 JVM 에서 끝나는 작업 줄을 남기려고 기다리는 시간.
 * VT 스케줄러(SimpleAsyncTaskScheduler)는 fixedRate·cron 작업을 close 에서 기다리지 않아서
 * 한 JVM 에서 조건을 이어 돌리면 앞 조건의 작업 줄이 다음 조건 로그에 섞인다. A3e 는 조건마다 JVM 을 따로 띄운다.
 */
private fun schedRun(label: String, profile: String, seconds: Long, vararg props: String, ms: Boolean = false, afterCloseMs: Long = 500) {
    println("## $label")
    SchedLog.start(ms)
    val ctx = boot(profile, props = arrayOf(*props))
    Thread.sleep(seconds * 1000)
    SchedLog.note("close() 호출")
    ctx.close()
    SchedLog.note("close() 반환")
    Thread.sleep(afterCloseMs)   // close 뒤에 끝나는 줄도 남긴다
    SchedLog.note("관측 끝(close 반환 ${afterCloseMs}ms 뒤)")
    SchedLog.dump()
}

private fun runA3() = schedRun("[A3] 기본 pool.size=1 · fixedRate 2s · 실행 3s", "rate", 11)
private fun runA3b() = schedRun("[A3b] pool.size=2 · 같은 작업", "rate", 11,
    "spring.task.scheduling.pool.size=2")
// A3c: delay 프로파일 = gamma(fixedDelay=500, 본문 3s) + delta(fixedRate=2000, 본문 3s)
private fun runA3c() = schedRun("[A3c] 가상 스레드 on · fixedRate(delta: 2000ms, 본문 3s) 와 fixedDelay(gamma: 500ms, 본문 3s) 대비", "delay", 11,
    "spring.threads.virtual.enabled=true")

private fun runA3d() {
    println("## [A3d] @Scheduled(fixedRate=1000) · 홀수 회차에 Boom — 예외 뒤에도 다음 주기가 도는가 (4.5초 관측)")
    SchedLog.start(ms = true)
    FlakyRuns.n.set(0)
    boot("err").use { ctx ->
        Thread.sleep(4500)
        println("실행 횟수 = ${FlakyRuns.n.get()}")
        ctx.getBeansOfType(ScheduledTaskHolder::class.java).forEach { (name, holder) ->
            holder.scheduledTasks.forEach { st ->
                val o = st.task.lastExecutionOutcome
                println("ScheduledTaskHolder[$name] ${st.task.runnable} lastExecutionOutcome: status=${o.status} " +
                    "executionTime=${o.executionTime} throwable=${o.throwable?.javaClass?.name}: ${o.throwable?.message}")
            }
        }
    }
    SchedLog.dump()
}

private fun runA3e(sel: Set<String>) {
    fun on(id: String) = sel.isEmpty() || id in sel
    val vt = "spring.threads.virtual.enabled=true"
    if (on("0")) schedRun("[A3e-0] VT on · 제한 없음 · delta(fixedRate 2s, 3s) epsil(cron */2, 3s) zeta(fixedRate 2s, 0.1s)",
        "ovl0", 11, vt, ms = true, afterCloseMs = 4000)
    if (on("a")) schedRun("[A3e-a] VT on · spring.task.scheduling.simple.concurrency-limit=1",
        "ovl0", 11, vt, "spring.task.scheduling.simple.concurrency-limit=1", ms = true, afterCloseMs = 4000)
    if (on("b")) schedRun("[A3e-b] VT on · @EnableResilientMethods + delta·epsil 에 @ConcurrencyLimit(1) (policy BLOCK)",
        "ovlb", 11, vt, ms = true, afterCloseMs = 4000)
    if (on("c")) schedRun("[A3e-c] VT on · @ConcurrencyLimit(limit=1, policy=REJECT)",
        "ovlc", 11, vt, ms = true, afterCloseMs = 4000)
    if (on("d")) schedRun("[A3e-d] VT off(ThreadPoolTaskScheduler 기본) · @Async + @Scheduled(fixedRate 2s), 본문 3s",
        "ovld", 11, ms = true, afterCloseMs = 4000)
}

// ------------------------------------------------------------------ [A4] 계열: 종료
/**
 * 작업을 넣고 closeAfterMs 뒤 close(). 시각은 작업 제출 시점(t=0) 기준 ms.
 * close 직후와 afterMs 뒤의 카운터·H2 행을 찍는다.
 */
private fun shutdownRun(
    label: String,
    props: Array<String>,
    profiles: Array<String> = emptyArray(),
    closeAfterMs: Long = 1000,
    afterMs: Long = 5000,
    submit: (ConfigurableApplicationContext) -> List<CompletableFuture<String>> = {
        it.getBean(AsyncProbe::class.java).longWork(4); emptyList()
    },
) {
    println("## $label")
    Counters.reset()
    Db.reset()
    val ctx = boot(*profiles, props = props)
    val t0 = System.currentTimeMillis()
    val futures = submit(ctx)
    Thread.sleep(closeAfterMs)
    println("close() 호출 t=${System.currentTimeMillis() - t0}ms")
    ctx.close()
    val ret = System.currentTimeMillis() - t0
    println("close() 반환 t=${ret}ms")
    println("close 직후: $Counters h2Rows=${Db.count()}")
    Thread.sleep(afterMs)
    println("${afterMs / 1000}초 뒤: $Counters h2Rows=${Db.count()}")
    futures.forEachIndexed { i, f ->
        val state = "isDone=${f.isDone} isCancelled=${f.isCancelled} isCompletedExceptionally=${f.isCompletedExceptionally}"
        val got = try { "get(1s)=" + f.get(1, TimeUnit.SECONDS) } catch (e: Throwable) { "get(1s) caught=${e.javaClass.name}" }
        println("  호출자 future #${i + 1}: $state $got")
    }
}

private val lifecycleInfo = "logging.level.org.springframework.context.support.DefaultLifecycleProcessor=INFO"

private fun runA4() = shutdownRun("[A4] 종료 기본값 — 실행 중이던 @Async 작업(4초), 1초 뒤 close()", emptyArray())
private fun runA4b() = shutdownRun("[A4b] await-termination=true · period=10s", arrayOf(
    "spring.task.execution.shutdown.await-termination=true",
    "spring.task.execution.shutdown.await-termination-period=10s"))

private fun runA4c(sel: Set<String>) {
    fun on(id: String) = sel.isEmpty() || id in sel
    if (on("a")) shutdownRun("[A4c-a] timeout-per-shutdown-phase=2s · 4초 @Async, 1초 뒤 close()",
        arrayOf("spring.lifecycle.timeout-per-shutdown-phase=2s", lifecycleInfo))
    for ((id, phase) in listOf("b30" to null, "b2" to "2s")) {
        if (!on(id)) continue
        val label = "[A4c-$id] @Scheduled(fixedRate=10000) 4초 잡이 도는 중 close() · phase timeout=${phase ?: "기본(Boot 30s)"}"
        println("## $label")
        Counters.reset(); Db.reset()
        SchedLog.start(ms = true)
        val ctx = boot("sched4", props = listOfNotNull(phase?.let { "spring.lifecycle.timeout-per-shutdown-phase=$it" }, lifecycleInfo).toTypedArray())
        SchedLog.note("refresh 끝")
        Thread.sleep(1000)
        SchedLog.note("close() 호출")
        ctx.close()
        SchedLog.note("close() 반환")
        println("close 직후: $Counters h2Rows=${Db.count()}")
        Thread.sleep(5000)
        SchedLog.dump()
        println("5초 뒤: $Counters h2Rows=${Db.count()}")
    }
}

private fun runA4d(sel: Set<String>) {
    fun on(id: String) = sel.isEmpty() || id in sel
    if (on("1")) shutdownRun("[A4d-1] await-termination=true · await-termination-period=1s", arrayOf(
        "spring.task.execution.shutdown.await-termination=true",
        "spring.task.execution.shutdown.await-termination-period=1s", lifecycleInfo))
    if (on("2")) shutdownRun("[A4d-2] await-termination=true 만 (period 없음)", arrayOf(
        "spring.task.execution.shutdown.await-termination=true", lifecycleInfo))
    if (on("3")) shutdownRun("[A4d-3] spring.task.execution.pool.shutdown.accept-tasks-after-context-close=true", arrayOf(
        "spring.task.execution.pool.shutdown.accept-tasks-after-context-close=true", lifecycleInfo))
}

private fun runA4e(sel: Set<String>) {
    fun on(id: String) = sel.isEmpty() || id in sel
    val vt = "spring.threads.virtual.enabled=true"
    val classLine: (ConfigurableApplicationContext) -> List<CompletableFuture<String>> = { ctx ->
        println("applicationTaskExecutor class=${ctx.getBean("applicationTaskExecutor").javaClass.name}")
        ctx.getBean(AsyncProbe::class.java).longWork(4); emptyList()
    }
    if (on("1")) shutdownRun("[A4e-1] VT on · 기본값", arrayOf(vt, lifecycleInfo), submit = classLine)
    if (on("2")) shutdownRun("[A4e-2] VT on · await-termination=true · period=10s", arrayOf(vt, lifecycleInfo,
        "spring.task.execution.shutdown.await-termination=true",
        "spring.task.execution.shutdown.await-termination-period=10s"), submit = classLine)
    if (on("3")) shutdownRun("[A4e-3] VT on · await-termination=true 만", arrayOf(vt, lifecycleInfo,
        "spring.task.execution.shutdown.await-termination=true"), submit = classLine)
    if (on("4")) shutdownRun("[A4e-4] VT on · spring.task.execution.simple.cancel-remaining-tasks-on-close=true", arrayOf(vt, lifecycleInfo,
        "spring.task.execution.simple.cancel-remaining-tasks-on-close=true"), submit = classLine)
}

private fun runA4g(sel: Set<String>) {
    fun on(id: String) = sel.isEmpty() || id in sel
    fun work(vararg ms: Long): (ConfigurableApplicationContext) -> List<CompletableFuture<String>> = { ctx ->
        val p = ctx.getBean(AsyncProbe::class.java); ms.forEach { p.longWorkMs(it) }; emptyList()
    }
    if (on("1")) shutdownRun("[A4g-1] core-size=1 · 2초 작업 3건 · 0.5초 뒤 close()",
        arrayOf("spring.task.execution.pool.core-size=1", lifecycleInfo), closeAfterMs = 500, afterMs = 6000,
        submit = work(2000, 2000, 2000))
    if (on("2")) shutdownRun("[A4g-2] core-size=2 · 2초·2.5초 엇갈린 작업 6건 · 0.5초 뒤 close()",
        arrayOf("spring.task.execution.pool.core-size=2", lifecycleInfo), closeAfterMs = 500, afterMs = 6000,
        submit = work(2000, 2500, 2000, 2500, 2000, 2500))
    if (on("3")) shutdownRun("[A4g-3] 기본 core 8 · 2초 작업 20건 · 0.5초 뒤 close()",
        arrayOf(lifecycleInfo), closeAfterMs = 500, afterMs = 6000,
        submit = work(*LongArray(20) { 2000 }))
    if (on("4")) shutdownRun("[A4g-4] core-size=1 · 2초 작업 3건 · await-termination=true · period=10s",
        arrayOf("spring.task.execution.pool.core-size=1", lifecycleInfo,
            "spring.task.execution.shutdown.await-termination=true",
            "spring.task.execution.shutdown.await-termination-period=10s"), closeAfterMs = 500, afterMs = 6000,
        submit = work(2000, 2000, 2000))
    if (on("5")) shutdownRun("[A4g-5] core-size=1 · 2초 CompletableFuture 작업 3건 · 0.5초 뒤 close() — 호출자 future",
        arrayOf("spring.task.execution.pool.core-size=1", lifecycleInfo), closeAfterMs = 500, afterMs = 6000) { ctx ->
        val p = ctx.getBean(AsyncProbe::class.java); (1..3).map { p.longWorkCf(2000) }
    }
}

/** A4f 서버 쪽. a4f_sigterm.py 가 java 로 직접 띄우고 SIGTERM 을 보낸다. main 은 exitProcess 하지 않는다. */
private fun serveA4f(vt: Boolean) {
    val props = mutableListOf(
        "spring.main.banner-mode=off",
        "spring.main.web-application-type=servlet",
        "server.port=0",
        "server.shutdown=graceful",
        "spring.lifecycle.timeout-per-shutdown-phase=3s",
        "spring.datasource.url=${Db.URL}",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "logging.level.root=WARN",
        "logging.level.org.springframework.context.support.DefaultLifecycleProcessor=INFO",
        "logging.level.org.springframework.boot.tomcat.GracefulShutdown=INFO",
        "logging.level.org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle=INFO",
    )
    if (vt) props += "spring.threads.virtual.enabled=true"
    val ctx = SpringApplicationBuilder(AsyncLabApp::class.java).profiles("web").properties(*props.toTypedArray()).run()
    println("applicationTaskExecutor class=${ctx.getBean("applicationTaskExecutor").javaClass.name}")
    ctx.getBeansOfType(org.springframework.context.SmartLifecycle::class.java).forEach { (n, b) ->
        println("SmartLifecycle bean=$n class=${b.javaClass.simpleName} phase=${b.phase}")
    }
    println("A4F_PORT=${ctx.environment.getProperty("local.server.port")}")
    println("A4F_PID=${ManagementFactory.getRuntimeMXBean().pid}")
}

// ------------------------------------------------------------------ [A5] 자기 호출
private fun runA5() {
    println("## [A5] 같은 객체 안에서 this.whereAmI() — Proxy 를 지나지 않는다")
    boot().use { ctx ->
        val p = ctx.getBean(AsyncProbe::class.java)
        println("AsyncProbe Bean class = ${p.javaClass.name}")
        println("외부에서 p.whereAmI()        → ${p.whereAmI().get(10, TimeUnit.SECONDS)}")
        println("p.callSelf() 안의 this.whereAmI() → ${p.callSelf()}")
    }
    println("## [A5-b] 필드 자기 주입 + spring.main.allow-circular-references=true")
    try {
        boot("selfinj", props = arrayOf("spring.main.allow-circular-references=true")).use { ctx ->
            val s = ctx.getBean(SelfInjAsync::class.java)
            val target = (s as org.springframework.aop.framework.Advised).targetSource.target as SelfInjAsync
            println("부팅됨. getBean 결과 class=${s.javaClass.name}")
            println("  target 의 self 필드 class=${target.self.javaClass.name} isAopProxy=${org.springframework.aop.support.AopUtils.isAopProxy(target.self)} (self === getBean 결과: ${target.self === s})")
            println("  target.self.hello() → ${target.self.hello().get(10, TimeUnit.SECONDS)}")
        }
    } catch (e: Throwable) {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        println("부팅 실패: ${e.javaClass.name}")
        println("  root cause: ${root.javaClass.name}: ${root.message}")
    }
    println("## [A5-c] @Lazy 필드 자기 주입 (allow-circular-references 기본값 false)")
    boot("selflazy").use { ctx ->
        val s = ctx.getBean(SelfLazyAsync::class.java)
        println("viaThis() = hello()      → ${s.viaThis()}")
        println("viaSelf() = self.hello() → ${s.viaSelf()}")
    }
}

fun main(args: Array<String>) {
    val marker = args.firstOrNull() ?: "A1"
    val sel = args.drop(1).toSet()
    runtimeHeader()
    when (marker) {
        "A1" -> runA1()
        "A1b" -> runA1b()
        "A1c" -> runA1c()
        "A1d" -> runA1d(sel)
        "A1e" -> runA1e(sel)
        "A2" -> runA2()
        "A2c" -> runA2c()
        "A2d" -> runA2d()
        "A2e" -> runA2e(sel)
        "A2eCtx" -> runA2eCtx()
        "A2f" -> runA2f()
        "A2g" -> runA2g()
        "A3" -> runA3()
        "A3b" -> runA3b()
        "A3c" -> runA3c()
        "A3d" -> runA3d()
        "A3e" -> runA3e(sel)
        "A4" -> runA4()
        "A4b" -> runA4b()
        "A4c" -> runA4c(sel)
        "A4d" -> runA4d(sel)
        "A4e" -> runA4e(sel)
        "A4fServe" -> { serveA4f(vt = "vt" in sel); return }   // SIGTERM 을 기다린다
        "A4g" -> runA4g(sel)
        "A5" -> runA5()
        "A6" -> runA6()
        else -> println("unknown marker: $marker")
    }
    exitProcess(0)
}
