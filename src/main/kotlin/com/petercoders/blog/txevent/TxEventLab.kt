package com.petercoders.blog.txevent

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.SpringBootVersion
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.SpringVersion
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronization
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource
import kotlin.system.exitProcess
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM

// SPRING-INTERNALS 1편 [e13] — @TransactionalEventListener 에 @Transactional 을 붙였을 때.
//   ./gradlew --offline -q txEventLab
// [e13]  부팅 검사: 6.1 RestrictedTransactionalEventListenerFactory 가 AFTER_COMMIT(BEFORE_COMMIT 외) 리스너의
//        @Transactional 을 REQUIRES_NEW·NOT_SUPPORTED 만 허용하는지(메서드 / 클래스 수준 / BEFORE_COMMIT / REQUIRES_NEW).
// [e13b] AFTER_COMMIT 리스너가 DB 에 쓴 행이 남는지: @Transactional 없음 / REQUIRES_NEW,
//        각각 Hikari autoCommit 기본(true) 과 spring.datasource.hikari.auto-commit=false.
//        리스너 안에서 Thread 의 Transaction 상태, 묶인 커넥션 유무, 그 커넥션의 autoCommit, 풀 active 수를 찍는다.
// [e13c] 커밋 뒤 콜백끼리의 순서: 직접 등록한 TransactionSynchronization 셋(order 없음 / 클래스에 @Order(0) /
//        getOrder()=0 override)과 AFTER_COMMIT 리스너 둘(메서드 @Order(1) / 없음)을 한 Transaction 에 걸고,
//        커밋 직전 getSynchronizations() 의 정렬 결과와 afterCommit()·afterCompletion()·리스너가 실제로 불린 순서를 찍는다.
// 이 패키지는 다른 랩의 scanBasePackages(com.petercoders.blog.tx 등) 밖이고, 빈은 전부 프로필 안에 있다.

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.txevent"])
class TxEventApp

data class Placed(val tag: String)

object Seen {
    val lines = CopyOnWriteArrayList<String>()
}

@Service
@Profile("txevent")
class OrderPlacer(private val jdbc: JdbcTemplate, private val events: ApplicationEventPublisher) {
    @Transactional
    fun place(tag: String) {
        jdbc.update("insert into ev_log(tag, note) values (?, 'outer')", tag)
        events.publishEvent(Placed(tag))
    }
}

/** 리스너 본문 공통: 지금 Thread 상태를 남기고 행 하나를 쓴다. */
abstract class ListenerBase(protected val jdbc: JdbcTemplate, protected val ds: DataSource) {
    protected fun writeAndRecord(e: Placed) {
        val con = DataSourceUtils.getConnection(ds)
        val autoCommit = try { con.autoCommit } finally { DataSourceUtils.releaseConnection(con, ds) }
        Seen.lines += "listener(${javaClass.simpleName.substringBefore("$$")}) thread=${Thread.currentThread().name} " +
            "actualTransactionActive=${TSM.isActualTransactionActive()} name=${TSM.getCurrentTransactionName()} " +
            "boundConnection=${TSM.hasResource(ds)} connection.autoCommit=$autoCommit " +
            "poolActive=${(ds as HikariDataSource).hikariPoolMXBean.activeConnections}"
        jdbc.update("insert into ev_log(tag, note) values (?, 'listener')", e.tag)
    }
}

@Component
@Profile("after-required")
class AfterCommitRequired(jdbc: JdbcTemplate, ds: DataSource) : ListenerBase(jdbc, ds) {
    @TransactionalEventListener            // phase 기본값 AFTER_COMMIT
    @Transactional                         // propagation 기본값 REQUIRED
    fun on(e: Placed) = writeAndRecord(e)
}

@Component
@Profile("class-tx")
@Transactional                             // 클래스 수준, 리스너 메서드에는 아무것도 안 붙였다
class ClassLevelTx(jdbc: JdbcTemplate, ds: DataSource) : ListenerBase(jdbc, ds) {
    @TransactionalEventListener
    fun on(e: Placed) = writeAndRecord(e)
}

@Component
@Profile("before-required")
class BeforeCommitRequired(jdbc: JdbcTemplate, ds: DataSource) : ListenerBase(jdbc, ds) {
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    @Transactional
    fun on(e: Placed) = writeAndRecord(e)
}

@Component
@Profile("after-requires-new")
class AfterCommitRequiresNew(jdbc: JdbcTemplate, ds: DataSource) : ListenerBase(jdbc, ds) {
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun on(e: Placed) = writeAndRecord(e)
}

@Component
@Profile("after-plain")
class AfterCommitPlain(jdbc: JdbcTemplate, ds: DataSource) : ListenerBase(jdbc, ds) {
    @TransactionalEventListener
    fun on(e: Placed) = writeAndRecord(e)
}

// ---------------------------------------------------------------- [e13c] 커밋 뒤 콜백 순서
data class Shipped(val tag: String)

/** afterCommit()·afterCompletion() 이 불릴 때 이름을 남긴다. order 는 하위 클래스마다 다르게 준다. */
abstract class RecordingSync(val label: String) : TransactionSynchronization {
    override fun afterCommit() { Seen.lines += "afterCommit()      $label" }
    override fun afterCompletion(status: Int) {
        Seen.lines += "afterCompletion()  $label (status=${if (status == TransactionSynchronization.STATUS_COMMITTED) "COMMITTED" else status})"
    }
}

class PlainSync : RecordingSync("PlainSync [no order]")

@Order(0)                                  // 클래스에 단 @Order. getOrder() 는 override 하지 않았다
class AnnotatedSync : RecordingSync("AnnotatedSync [@Order(0) on class]")

class OverrideSync : RecordingSync("OverrideSync [getOrder() = 0]") {
    override fun getOrder(): Int = 0
}

@Component
@Profile("order")
class OrderedListeners {
    @TransactionalEventListener            // AFTER_COMMIT
    @Order(1)
    fun first(e: Shipped) { Seen.lines += "AFTER_COMMIT listener  first [@Order(1) on method]" }

    @TransactionalEventListener            // AFTER_COMMIT, @Order 없음
    fun second(e: Shipped) { Seen.lines += "AFTER_COMMIT listener  second [no @Order]" }
}

@Service
@Profile("order")
class SyncRegistrar(private val events: ApplicationEventPublisher) {
    @Transactional
    fun execute() {
        TSM.registerSynchronization(PlainSync())      // 등록 순서: Plain → Annotated → Override → 리스너 둘
        TSM.registerSynchronization(AnnotatedSync())
        TSM.registerSynchronization(OverrideSync())
        events.publishEvent(Shipped("e13c"))
        TSM.getSynchronizations().forEachIndexed { i, s ->
            val name = if (s is RecordingSync) s.label else s.javaClass.name.substringAfterLast('.')
            val order = if (s.order == Ordered.LOWEST_PRECEDENCE) "LOWEST_PRECEDENCE" else s.order.toString()
            Seen.lines += "  sorted[$i] $name  getOrder()=$order"
        }
    }
}

private fun boot(profile: String, vararg extra: String): Pair<ConfigurableApplicationContext?, String> =
    try {
        SpringApplicationBuilder(TxEventApp::class.java).properties(
            "spring.main.web-application-type=none",
            "spring.main.banner-mode=off",
            "spring.datasource.url=jdbc:h2:mem:txevent-$profile-${extra.size};DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "logging.level.root=WARN",
            "logging.level.org.springframework.boot.SpringApplication=OFF",
            "logging.level.org.springframework.boot.diagnostics=OFF",
            "spring.profiles.active=txevent,$profile",
            *extra,
        ).run() to "booted"
    } catch (e: Throwable) {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        null to ("boot failed: ${e.javaClass.name}\n    root cause: ${root.javaClass.name}: " +
            (root.message?.lineSequence()?.first() ?: ""))
    }

private fun implVersion(className: String, pom: String): String {
    val c = Class.forName(className)
    c.`package`.implementationVersion?.let { return it }
    val props = java.util.Properties()
    c.classLoader.getResourceAsStream(pom)?.use { props.load(it) }
    return props.getProperty("version") ?: "unknown"
}

fun main() {
    println("## [e0] runtime versions (printed by this JVM)")
    println("java.runtime.version = ${System.getProperty("java.runtime.version")}")
    println("java.vendor          = ${System.getProperty("java.vendor")} / ${System.getProperty("java.vm.name")}")
    println("Spring Framework     = ${SpringVersion.getVersion()}")
    println("Spring Boot          = ${SpringBootVersion.getVersion()}")
    println("Kotlin stdlib        = ${KotlinVersion.CURRENT}")
    println("java.home            = ${System.getProperty("java.home")}")
    println("HikariCP             = ${implVersion("com.zaxxer.hikari.HikariDataSource", "META-INF/maven/com.zaxxer/HikariCP/pom.properties")}")
    println("H2                   = ${implVersion("org.h2.Driver", "META-INF/maven/com.h2database/h2/pom.properties")}")
    println()

    println("## [e13] boot check: @TransactionalEventListener + @Transactional (RestrictedTransactionalEventListenerFactory, 6.1+)")
    for ((profile, label) in listOf(
        "after-required" to "(a) AFTER_COMMIT listener + method @Transactional (REQUIRED)",
        "class-tx" to "(b) class-level @Transactional + AFTER_COMMIT listener method",
        "before-required" to "(c) BEFORE_COMMIT listener + method @Transactional (REQUIRED)",
        "after-requires-new" to "(d) AFTER_COMMIT listener + @Transactional(propagation = REQUIRES_NEW)",
    )) {
        val (ctx, result) = boot(profile)
        println("$label -> $result")
        if (ctx != null) {
            println("    listener factory = " +
                ctx.getBean("org.springframework.transaction.config.internalTransactionalEventListenerFactory").javaClass.name)
            ctx.close()
        }
    }
    println()

    println("## [e13b] where does an AFTER_COMMIT listener's write go (outer inserts 'outer', listener inserts 'listener')")
    for ((profile, extra, label) in listOf(
        Triple("after-plain", arrayOf<String>(), "(a) AFTER_COMMIT, no @Transactional, Hikari autoCommit default(true)"),
        Triple("after-plain", arrayOf("spring.datasource.hikari.auto-commit=false",
            "logging.level.com.zaxxer.hikari.pool.ProxyConnection=DEBUG"),
            "(b) AFTER_COMMIT, no @Transactional, spring.datasource.hikari.auto-commit=false"),
        Triple("after-requires-new", arrayOf<String>(), "(c) AFTER_COMMIT, @Transactional(REQUIRES_NEW), Hikari autoCommit default(true)"),
        Triple("after-requires-new", arrayOf("spring.datasource.hikari.auto-commit=false"),
            "(d) AFTER_COMMIT, @Transactional(REQUIRES_NEW), spring.datasource.hikari.auto-commit=false"),
        Triple("before-required", arrayOf<String>(), "(e) BEFORE_COMMIT, @Transactional(REQUIRED), Hikari autoCommit default(true)"),
    )) {
        println()
        println("### [e13b] $label")
        val (ctx, result) = boot(profile, *extra)
        if (ctx == null) { println(result); continue }
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        val ds = ctx.getBean(DataSource::class.java) as HikariDataSource
        jdbc.execute("drop table if exists ev_log")
        jdbc.execute("create table ev_log(id identity primary key, tag varchar(40), note varchar(40))")
        println("hikari autoCommit = ${ds.isAutoCommit}")
        Seen.lines.clear()
        val outcome = try {
            ctx.getBean(OrderPlacer::class.java).place(profile); "returned normally"
        } catch (e: Throwable) {
            "threw ${e.javaClass.name}: ${e.message?.lineSequence()?.first()}"
        }
        Seen.lines.forEach { println(it) }
        println("caller: $outcome")
        val notes = jdbc.queryForList("select note from ev_log where tag = ? order by id", String::class.java, profile)
        println("rows committed = ${notes.size} $notes   (counted afterwards on a fresh connection)")
        println("poolActive after call = ${ds.hikariPoolMXBean.activeConnections}")
        ctx.close()
    }
    println()

    println("## [e13c] order of after-commit callbacks (registered: PlainSync, AnnotatedSync, OverrideSync, then publishEvent -> listeners first, second)")
    run {
        val (ctx, result) = boot("order")
        if (ctx == null) { println(result); return@run }
        Seen.lines.clear()
        ctx.getBean(SyncRegistrar::class.java).execute()
        println("getSynchronizations() just before commit, inside the transaction:")
        Seen.lines.filter { it.startsWith("  sorted[") }.forEach { println(it) }
        println("actual call order after commit:")
        Seen.lines.filterNot { it.startsWith("  sorted[") }.forEachIndexed { i, l -> println("  ${i + 1}. $l") }
        ctx.close()
    }
    exitProcess(0)
}
