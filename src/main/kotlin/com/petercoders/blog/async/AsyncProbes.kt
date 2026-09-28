// SPRING-INTERNALS 5편(@Async·@Scheduled) — 프로브 빈.
// 판정 증거는 결과다. 로그 유무가 아니라 카운터·H2 행·예외 객체의 실제 클래스명으로 증명한다.
package com.petercoders.blog.async

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 종료 실험의 증거. JVM 이 사는 동안 컨텍스트 close 후에도 남는다. */
object Counters {
    val finished = AtomicInteger()
    val interrupted = AtomicInteger()
    fun reset() { finished.set(0); interrupted.set(0) }
}

/** 스케줄러 실험의 증거. "겹쳤나"만 본다 — 초 단위 굵기. */
object SchedLog {
    @Volatile var t0 = 0L
    val lines = ConcurrentLinkedQueue<String>()
    fun start() { t0 = System.currentTimeMillis(); lines.clear() }
    fun mark(tag: String, phase: String) {
        val sec = (System.currentTimeMillis() - t0) / 1000
        lines += "t=%2ds %-6s %-5s thread=%s".format(sec, tag, phase, Thread.currentThread().name)
    }
    fun dump() = lines.forEach(::println)
}

class Boom(msg: String) : IllegalStateException(msg)

/**
 * 종료 실험의 두 번째 증거. 컨텍스트가 닫히면 DataSource 도 닫히므로
 * JdbcTemplate 대신 DriverManager 로 직접 연결한다(DB_CLOSE_DELAY=-1 이라 JVM 이 살아 있는 동안 남는다).
 */
object Db {
    const val URL = "jdbc:h2:mem:asynclab;DB_CLOSE_DELAY=-1"
    fun reset() = DriverManager.getConnection(URL).use {
        it.createStatement().execute("drop table if exists async_done")
        it.createStatement().execute("create table async_done(id identity primary key, tag varchar(40))")
    }
    fun insert(tag: String) = DriverManager.getConnection(URL).use {
        it.prepareStatement("insert into async_done(tag) values (?)").use { ps ->
            ps.setString(1, tag); ps.executeUpdate()
        }
    }
    fun count(): Int = DriverManager.getConnection(URL).use {
        it.createStatement().executeQuery("select count(*) from async_done").use { rs ->
            rs.next(); rs.getInt(1)
        }
    }
}

@Component
class AsyncProbe {

    /** 어느 실행자 위에서 도는지 결과로 확인한다. */
    @Async
    fun whereAmI(): CompletableFuture<String> {
        val t = Thread.currentThread()
        return CompletableFuture.completedFuture("name=${t.name} virtual=${t.isVirtual}")
    }

    /** 선언 반환 타입이 CompletableFuture 라서 submitCompletable 경로를 탄다. */
    @Async
    fun failCompletable(): CompletableFuture<String> = throw Boom("boom-completable")

    /** 선언 반환 타입이 Future 라서 submit 경로를 탄다(FutureTask). */
    @Async
    fun failFuture(): Future<String> = throw Boom("boom-future")

    /** 반환 타입이 kotlin.Unit — handleError 가 핸들러 쪽으로 보낸다. */
    @Async
    fun failUnit() { throw Boom("boom-unit") }

    @Async
    fun sleepThen(seconds: Long): CompletableFuture<String> {
        Thread.sleep(seconds * 1000)
        return CompletableFuture.completedFuture("slept ${seconds}s")
    }

    /** 종료 실험용. 끝까지 돌면 카운터와 H2 행을 남긴다. */
    @Async
    fun longWork(seconds: Long) {
        try {
            Thread.sleep(seconds * 1000)
            Counters.finished.incrementAndGet()
            Db.insert("longWork")
        } catch (e: InterruptedException) {
            Counters.interrupted.incrementAndGet()
            Thread.currentThread().interrupt()
        }
    }
}

/** A3/A3b — fixedRate: 주기 2초, 실행 3초. 겹치는지 본다. */
@Component
@Profile("rate")
class RateProbes {
    @Scheduled(fixedRate = 2000)
    fun alpha() { SchedLog.mark("alpha", "start"); Thread.sleep(3000); SchedLog.mark("alpha", "end") }

    @Scheduled(fixedRate = 2000)
    fun beta() { SchedLog.mark("beta", "start"); Thread.sleep(3000); SchedLog.mark("beta", "end") }
}

/** A3c — fixedDelay: 가상 스레드에서도 스케줄러 스레드를 붙잡는지 본다. */
@Component
@Profile("delay")
class DelayProbes {
    @Scheduled(fixedDelay = 500)
    fun gamma() { SchedLog.mark("gamma", "start"); Thread.sleep(3000); SchedLog.mark("gamma", "end") }

    @Scheduled(fixedRate = 2000)
    fun delta() { SchedLog.mark("delta", "start"); Thread.sleep(3000); SchedLog.mark("delta", "end") }
}

/** A1c — 사용자가 Executor 빈을 하나라도 등록하면 무슨 일이 생기는지. */
@Configuration(proxyBeanMethods = false)
@Profile("custom")
class CustomExecutorConfig {
    @Bean
    fun myOwnExecutor(): Executor = Executors.newFixedThreadPool(2)
}
