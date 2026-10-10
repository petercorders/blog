package com.petercoders.blog.kcolab.e26

import io.micrometer.core.annotation.Timed
import io.micrometer.core.aop.TimedAspect
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

// kco-e26 — Micrometer @Timed(TimedAspect) 는 suspend fun 의 어디까지를 재나.
// 본문: 첫 suspension 전에 Thread.sleep(100), 그 뒤 delay(400). (a) ~0 ms (b) ~100 ms (c) ~500 ms 로 갈린다.
@SpringBootApplication
class E26App {
    @Bean fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    @Bean fun timedAspect(registry: MeterRegistry) = TimedAspect(registry)
}

@Service
class TimedSvc(private val registry: MeterRegistry) {
    @Timed("kco.timed")
    suspend fun slow(): String { Thread.sleep(100); delay(400); return "ok" }

    suspend fun manual(): String {
        val sample = Timer.start(registry)
        try { Thread.sleep(100); delay(400); return "ok" } finally { sample.stop(registry.timer("kco.manual")) }
    }
}

fun run() {
    val ctx = SpringApplicationBuilder(E26App::class.java).web(WebApplicationType.NONE)
        .properties("spring.main.banner-mode=off", "logging.level.root=WARN").run()
    val svc = ctx.getBean(TimedSvc::class.java); val registry = ctx.getBean(MeterRegistry::class.java)
    println("TimedSvc bean = ${svc.javaClass.name}")
    runBlocking {
        repeat(3) { i ->
            val t0 = System.nanoTime(); svc.slow(); val wallSlow = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime(); svc.manual(); val wallManual = (System.nanoTime() - t1) / 1_000_000
            println("call ${i + 1}: wall slow()=$wallSlow ms  wall manual()=$wallManual ms")
        }
    }
    for (name in listOf("kco.timed", "kco.manual")) {
        val t = registry.find(name).timer()
        println("timer $name: count=${t?.count()}  mean=%.1f ms  max=%.1f ms".format(t?.mean(TimeUnit.MILLISECONDS) ?: -1.0, t?.max(TimeUnit.MILLISECONDS) ?: -1.0))
    }
    ctx.close()
}
