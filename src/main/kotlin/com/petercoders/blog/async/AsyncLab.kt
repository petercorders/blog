// SPRING-INTERNALS 5편(@Async·@Scheduled) — 실험 러너.
// 마커 → 로그 파일: [A1]→a1.log · [A1b]→a1b.log · [A1c]→a1c.log · [A2]→a2.log
//                   [A2c]→a2.log · [A3]→a3.log · [A3b]→a3b.log · [A3c]→a3c.log
//                   [A4]→a4.log · [A4b]→a4b.log
//
// 실행: cd ~/Desktop/development/blog
//       ./gradlew --offline -q asyncLab --args='A1'
//
// 판정은 로그 유무가 아니라 결과다 — 등록된 빈의 실제 클래스, 잡힌 예외 객체의 클래스명,
// 카운터 값, H2 행 수. 시간은 초 단위 굵기로만 본다(같은 기계에서 다른 빌드가 돈다).
package com.petercoders.blog.async

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import kotlin.system.exitProcess

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.async"])
@EnableAsync
@EnableScheduling
class AsyncLabApp

private fun boot(vararg profiles: String, props: Array<String> = emptyArray()): ConfigurableApplicationContext =
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
    when (val s = ctx.getBean("taskScheduler")) {
        is ThreadPoolTaskScheduler -> println("taskScheduler class=${s.javaClass.name} poolSize=${s.poolSize}")
        is SimpleAsyncTaskScheduler -> println("taskScheduler class=${s.javaClass.name} (풀 크기 개념 없음)")
        else -> println("taskScheduler class=${s.javaClass.name}")
    }
    println("Executor 타입 빈 = ${ctx.getBeanNamesForType(Executor::class.java).sorted()}")
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

// ------------------------------------------------------------------ [A2] [A2c]
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
        try { f.get(1, TimeUnit.SECONDS) } catch (e: TimeoutException) {
            println("get(1,SECONDS) caught=${e.javaClass.name} cause=${e.cause?.javaClass?.name}")
        }
        println("타임아웃 이후 future 상태: isDone=${f.isDone} isCancelled=${f.isCancelled}")
    }
}

// ------------------------------------------------------------------ [A3] [A3b] [A3c]
private fun schedRun(label: String, profile: String, seconds: Long, vararg props: String) {
    println("## $label")
    SchedLog.start()
    boot(profile, props = arrayOf(*props)).use { Thread.sleep(seconds * 1000) }
    SchedLog.dump()
}

private fun runA3() = schedRun("[A3] 기본 pool.size=1 · fixedRate 2s · 실행 3s", "rate", 11)
private fun runA3b() = schedRun("[A3b] pool.size=2 · 같은 작업", "rate", 11,
    "spring.task.scheduling.pool.size=2")
private fun runA3c() = schedRun("[A3c] 가상 스레드 on · fixedRate 와 fixedDelay 대비", "delay", 11,
    "spring.threads.virtual.enabled=true")

// ------------------------------------------------------------------ [A4] [A4b]
private fun shutdownRun(label: String, vararg props: String) {
    println("## $label")
    Counters.reset()
    Db.reset()
    val ctx = boot(props = arrayOf(*props))
    ctx.getBean(AsyncProbe::class.java).longWork(4)
    Thread.sleep(1000)
    val t0 = System.currentTimeMillis()
    ctx.close()
    println("close() 가 반환할 때까지 = ${(System.currentTimeMillis() - t0) / 1000}초")
    Thread.sleep(5000)
    println("finished=${Counters.finished.get()} interrupted=${Counters.interrupted.get()} h2Rows=${Db.count()}")
}

private fun runA4() = shutdownRun("[A4] 종료 기본값 — 실행 중이던 @Async 작업")
private fun runA4b() = shutdownRun("[A4b] await-termination=true · period=10s",
    "spring.task.execution.shutdown.await-termination=true",
    "spring.task.execution.shutdown.await-termination-period=10s")

fun main(args: Array<String>) {
    when (args.firstOrNull() ?: "A1") {
        "A1" -> runA1()
        "A1b" -> runA1b()
        "A1c" -> runA1c()
        "A2" -> runA2()
        "A2c" -> runA2c()
        "A3" -> runA3()
        "A3b" -> runA3b()
        "A3c" -> runA3c()
        "A4" -> runA4()
        "A4b" -> runA4b()
        else -> println("unknown marker: ${args.firstOrNull()}")
    }
    exitProcess(0)
}
