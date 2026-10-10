// SPRING-INTERNALS 5편 — [A6] actuator 가 클래스패스에 있을 때 executor 메트릭 자동 바인딩.
// 이 파일만 io.micrometer.core 를 참조한다. actuator 는 build.gradle.kts 의 asyncLabObs configuration 에만 있다.
// ./gradlew --offline -q asyncLabObs --args='A6'
package com.petercoders.blog.async

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

private fun a6Case(label: String, profiles: Array<String>, props: Array<String>, holdCheck: Boolean = false) {
    println("## $label")
    boot(*profiles, props = props).use { ctx ->
        val reg = ctx.getBean(MeterRegistry::class.java)
        println("  MeterRegistry = ${reg.javaClass.name}")
        println("  applicationTaskExecutor = ${if (ctx.containsBean("applicationTaskExecutor")) ctx.getBean("applicationTaskExecutor").javaClass.simpleName else "없음"}" +
            " / taskScheduler = ${if (ctx.containsBean("taskScheduler")) ctx.getBean("taskScheduler").javaClass.simpleName else "없음"}")
        val meters = reg.meters.filter { it.id.name.startsWith("executor") }
        if (meters.isEmpty()) println("  executor* 메터 없음")
        meters.groupBy { it.id.getTag("name") ?: "(name 태그 없음)" }.toSortedMap().forEach { (n, ms) ->
            println("  name=$n : ${ms.map { it.id.name }.distinct().sorted()}")
        }
        if (holdCheck) {
            val p = ctx.getBean(AsyncProbe::class.java)
            val ex = ctx.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor::class.java)
            Hold.reset()
            val fs = mutableListOf<CompletableFuture<String>>()
            repeat(20) { fs += p.hold() }
            Thread.sleep(300)
            val queued = reg.find("executor.queued").tag("name", "applicationTaskExecutor").gauge()?.value()
            val active = reg.find("executor.active").tag("name", "applicationTaskExecutor").gauge()?.value()
            val pool = reg.find("executor.pool.size").tag("name", "applicationTaskExecutor").gauge()?.value()
            println("  hold() 20건 300ms 뒤: executor.queued=$queued getQueueSize()=${ex.queueSize} " +
                "executor.active=$active activeCount=${ex.activeCount} executor.pool.size=$pool poolSize=${ex.poolSize}")
            Hold.release()
            fs.forEach { it.get(20, TimeUnit.SECONDS) }
        }
    }
}

fun runA6() {
    a6Case("[A6-1] 기본 앱(applicationTaskExecutor, taskScheduler)", emptyArray(), emptyArray(), holdCheck = true)
    a6Case("[A6-2] + reportExecutor(@Bean(defaultCandidate=false), Boot builder)", arrayOf("e-report"), emptyArray())
    a6Case("[A6-3] + ExecutorService Bean(A1c 모양)", arrayOf("custom"), emptyArray())
    a6Case("[A6-4] spring.threads.virtual.enabled=true", emptyArray(), arrayOf("spring.threads.virtual.enabled=true"))
}
