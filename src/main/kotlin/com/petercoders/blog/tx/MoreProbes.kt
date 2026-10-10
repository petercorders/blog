// SPRING-INTERNALS 1편 — 재편 때 더한 실험용 빈. 구간 마커는 로그 파일 id 와 같다.
//   [e1c] ShipService(@Proxyable), JdkInjectProbe            — Proxy 종류를 false 로 바꿨을 때의 대가
//   [e2d] SelfFieldProbe / LazySelfProbe / ProviderSelfProbe — 순환 참조를 끈 상태의 self 주입 세 갈래
//   [e2e] ExposeProxyPostProcessor + ExposeProbe             — exposeProxy + AopContext.currentProxy()
//   [e2g] PostConstructSelfProbe / PostConstructProviderProbe — @PostConstruct 에서 Proxy 경유 호출
//   [e5b] KvRepo / KvUpsertService, NoGlobalRollbackConfig   — 중복 키 upsert, 참여 실패 플래그 끄기
//   [e5c] PoolProbe                                          — REQUIRES_NEW·NESTED 커넥션 수, 풀 교착
//   [e6c] AsyncWriter / HandoffProbe                         — 다른 Thread 로 넘긴 쓰기
//   [e7d] CheckedPort / CheckedPortImpl                      — 인터페이스 Bean(JDK Proxy 갈래)
//   [e8b] FinalOverrideProbe                                 — 인터페이스 구현 final 메서드 경고
//   [e10] CountingTxListener                                 — TransactionExecutionListener
//   [e12] Routing*Config / RoutingProbe                      — readOnly 라우팅
// 프로필이 없는 빈은 모든 tx 컨텍스트에 뜬다. self 필드를 가진 빈은 @Profile("!circ-off").
package com.petercoders.blog.tx

import com.zaxxer.hikari.HikariDataSource
import jakarta.annotation.PostConstruct
import org.springframework.aop.config.AopConfigUtils
import org.springframework.aop.framework.AopContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.context.annotation.ProxyType
import org.springframework.context.annotation.Proxyable
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

// ---------------------------------------------------------------- [e1c] Proxy 종류를 바꿨을 때

interface ShipPort {
    fun ship(): Hit
}

/** 7.0 @Proxyable: 전역 기본값(proxy-target-class)과 상관없이 이 Bean 만 CGLIB 로 만들라고 요청한다. */
@Service
@Proxyable(ProxyType.TARGET_CLASS)
class ShipService : ShipPort {
    @Transactional
    override fun ship(): Hit = hit("@Proxyable(TARGET_CLASS) bean", true)
}

/** 구현 클래스 타입(OrderService)으로 생성자 주입을 받는다. proxy-target-class=false 컨텍스트에서만 띄운다. */
@Component
@Profile("jdkinject")
class JdkInjectProbe(val orderService: OrderService)

// ---------------------------------------------------------------- [e2d] 순환 참조를 끈 상태의 self 주입

@Service
@Profile("circ-field")
class SelfFieldProbe {
    @Autowired
    lateinit var self: SelfFieldProbe

    @Transactional
    fun tx(): Hit = hit("self field", true)

    fun viaSelf(): Hit = self.tx().copy(case = "(a) @Autowired lateinit var self")
}

@Service
@Profile("circ-lazy")
class LazySelfProbe {
    @Autowired
    @Lazy
    lateinit var self: LazySelfProbe

    @Transactional
    fun tx(): Hit = hit("@Lazy self", true)

    fun viaSelf(): Hit = self.tx().copy(case = "(b) @Autowired @Lazy lateinit var self")
}

@Service
@Profile("circ-provider")
class ProviderSelfProbe(private val me: ObjectProvider<ProviderSelfProbe>) {
    @Transactional
    fun tx(): Hit = hit("ObjectProvider self", true)

    fun viaSelf(): Hit = me.getObject().tx().copy(case = "(c) ObjectProvider<Self>.getObject()")
}

// ---------------------------------------------------------------- [e2e] exposeProxy

/** @EnableAspectJAutoProxy(exposeProxy = true) 와 같은 일을 자동 프록시 생성기 Bean 정의에 직접 한다. */
@Component
@Profile("expose")
class ExposeProxyPostProcessor : BeanFactoryPostProcessor {
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        AopConfigUtils.forceAutoProxyCreatorToExposeProxy(beanFactory as BeanDefinitionRegistry)
    }
}

@Service
@Profile("expose")
class ExposeProbe {
    @Transactional
    fun tx(): Hit = hit("tx", true)

    fun viaCurrentProxy(): Hit =
        (AopContext.currentProxy() as ExposeProbe).tx().copy(case = "(AopContext.currentProxy() as ExposeProbe).tx()")

    fun selfInvoke(): Hit = tx().copy(case = "this.tx() (exposeProxy 켜도 자기 호출)")
}

// ---------------------------------------------------------------- [e2g] @PostConstruct 에서 Proxy 경유 호출

/** 순환 참조 허용 + self 필드: self 는 early Proxy 다. @PostConstruct 안에서 self.tx() 를 부른다. */
@Service
@Profile("pc-self")
class PostConstructSelfProbe {
    @Autowired
    lateinit var self: PostConstructSelfProbe

    var atInit: Hit? = null

    @PostConstruct
    fun init() {
        atInit = self.tx().copy(case = "@PostConstruct → self.tx() (allow-circular-references=true)")
    }

    @Transactional
    fun tx(): Hit = hit("tx", true)
}

/** 순환 참조 금지 + ObjectProvider: @PostConstruct 안에서 아직 생성 중인 자기 자신을 꺼낸다. */
@Service
@Profile("pc-provider")
class PostConstructProviderProbe(private val me: ObjectProvider<PostConstructProviderProbe>) {
    var atInit: String = "-"

    @PostConstruct
    fun init() {
        atInit = try {
            val h = me.getObject().tx()
            "active=${h.active} name=${h.txName}"
        } catch (e: Throwable) {
            "${e.javaClass.name}: ${e.message?.lineSequence()?.first()}"
        }
    }

    @Transactional
    fun tx(): Hit = hit("tx", true)
}

// ---------------------------------------------------------------- [e5b] 중복 키 upsert, 참여 실패 플래그

/** 내가 붙이지 않은 @Transactional 의 대역: 저장소의 insert 가 클래스·메서드 레벨 @Transactional 을 갖는다. */
@Service
class KvRepo(private val jdbc: JdbcTemplate) {
    @Transactional
    fun insert(k: String, v: Int) {
        jdbc.update("insert into tx_kv(k, v) values (?, ?)", k, v)
    }
}

@Service
class KvUpsertService(private val repo: KvRepo, private val jdbc: JdbcTemplate) {
    /** upsert 안에서 실제로 catch 한 예외 클래스(반환값은 커밋 실패로 호출자에게 닿지 않으므로 따로 남긴다). */
    var lastCaught: String = "-"

    /** 중복 키 예외를 catch 해 update 로 넘어가는 upsert. */
    @Transactional
    fun upsert(k: String, v: Int): String =
        try {
            repo.insert(k, v)
            "inserted"
        } catch (e: DataIntegrityViolationException) {
            lastCaught = e.javaClass.name
            jdbc.update("update tx_kv set v = ? where k = ?", v, k)
            "caught ${e.javaClass.simpleName} -> updated"
        }
}

/** Boot 프로퍼티가 없어 TransactionManagerCustomizer Bean 으로만 바꾼다. */
@Configuration(proxyBeanMethods = false)
@Profile("noglobal")
class NoGlobalRollbackConfig {
    @Bean
    fun noGlobalRollback(): TransactionManagerCustomizer<AbstractPlatformTransactionManager> =
        object : TransactionManagerCustomizer<AbstractPlatformTransactionManager> {
            override fun customize(transactionManager: AbstractPlatformTransactionManager) {
                transactionManager.isGlobalRollbackOnParticipationFailure = false
            }
        }
}

// ---------------------------------------------------------------- [e5c] 커넥션 수와 풀 교착

@Service
@Profile("!circ-off")
class PoolProbe(private val jdbc: JdbcTemplate, private val ds: DataSource) {

    @Autowired
    lateinit var self: PoolProbe

    fun active(): Int = (ds as HikariDataSource).hikariPoolMXBean.activeConnections

    @Transactional
    fun outerRequiresNew(tag: String): String {
        val outer = active()
        jdbc.update("insert into tx_log(tag, note) values (?, 'outer')", tag)
        val inner = self.innerRequiresNew(tag)
        return "outer body active=$outer, REQUIRES_NEW inner body active=$inner"
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun innerRequiresNew(tag: String): Int {
        jdbc.update("insert into tx_log(tag, note) values (?, 'inner')", tag)
        return active()
    }

    @Transactional
    fun outerNested(tag: String): String {
        val outer = active()
        jdbc.update("insert into tx_log(tag, note) values (?, 'outer')", tag)
        val inner = self.innerNested(tag)
        return "outer body active=$outer, NESTED inner body active=$inner"
    }

    @Transactional(propagation = Propagation.NESTED)
    fun innerNested(tag: String): Int {
        jdbc.update("insert into tx_log(tag, note) values (?, 'inner')", tag)
        return active()
    }

    /** 두 Thread 가 바깥 커넥션을 하나씩 쥔 채 만나고, 그 다음 안쪽 REQUIRES_NEW 를 연다. */
    @Transactional
    fun outerThenBarrier(tag: String, barrier: CyclicBarrier): String {
        jdbc.update("insert into tx_log(tag, note) values (?, 'outer')", tag)
        barrier.await(5, TimeUnit.SECONDS)
        val inner = self.innerRequiresNew(tag)
        return "ok (inner body active=$inner)"
    }
}

// ---------------------------------------------------------------- [e6c] 다른 Thread 로 넘긴 쓰기

@Configuration(proxyBeanMethods = false)
@Profile("async")
@EnableAsync
class AsyncOnConfig

data class Seen(val path: String, val thread: String, val active: Boolean)

@Service
@Profile("async")
class AsyncWriter(private val jdbc: JdbcTemplate) {
    @Async
    fun write(tag: String): CompletableFuture<Seen> {
        val s = Seen("@Async", Thread.currentThread().name, TSM.isActualTransactionActive())
        jdbc.update("insert into tx_log(tag, note) values (?, ?)", tag, s.thread.take(40))
        return CompletableFuture.completedFuture(s)
    }
}

class OuterFailed : RuntimeException("outer rolls back on purpose")

@Service
@Profile("async")
class HandoffProbe(private val jdbc: JdbcTemplate, private val asyncWriter: AsyncWriter) {

    val seen = CopyOnWriteArrayList<Seen>()

    private fun write(path: String, tag: String) {
        val s = Seen(path, Thread.currentThread().name, TSM.isActualTransactionActive())
        seen += s
        jdbc.update("insert into tx_log(tag, note) values (?, ?)", tag, s.thread.take(40))
    }

    /** 바깥 Transaction 안에서 네 경로로 쓰기를 넘긴 뒤, 마지막에 예외로 바깥을 롤백한다. */
    @Transactional
    fun handoffThenRollback() {
        write("caller", "e6c-caller")
        seen += asyncWriter.write("e6c-async").get()
        CompletableFuture.supplyAsync { write("CompletableFuture.supplyAsync", "e6c-cf") }.get()
        (1..16).toList().parallelStream().forEach {
            Thread.sleep(5)
            write("parallelStream", "e6c-par")
        }
        val t = Thread({ write("new Thread", "e6c-thread") }, "manual-thread")
        t.start(); t.join()
        throw OuterFailed()
    }

    /** [e6d] 바깥이 갱신해 락을 쥔 행을 작업 Thread 가 갱신한다. */
    @Transactional
    fun lockThenHandoff(): String {
        jdbc.update("update tx_log set note = 'outer' where tag = 'e6d'")
        return try {
            CompletableFuture.runAsync { jdbc.update("update tx_log set note = 'worker' where tag = 'e6d'") }.get()
            "worker updated"
        } catch (e: ExecutionException) {
            val c = e.cause!!
            var root: Throwable = c
            while (root.cause != null && root.cause !== root) root = root.cause!!
            "${c.javaClass.name}: ${c.message?.lineSequence()?.first()}\n    root cause: ${root.javaClass.name}"
        }
    }
}

// ---------------------------------------------------------------- [e7d] 인터페이스 Bean 의 checked 예외

interface CheckedPort {
    fun javaChecked(tag: String)
}

@Service
class CheckedPortImpl(private val jdbc: JdbcTemplate) : CheckedPort {
    @Transactional
    override fun javaChecked(tag: String) {
        jdbc.update("insert into tx_log(tag, note) values (?, 'r')", tag)
        java.nio.file.Files.readString(java.nio.file.Path.of("/nonexistent-tx-lab"))
    }
}

// ---------------------------------------------------------------- [e8b] 인터페이스 구현 final 메서드

interface Pingable {
    fun ping(): String
}

@Service
@Profile("loglab")
class FinalOverrideProbe : Pingable {
    @Transactional
    final override fun ping(): String = "pong"
}

// ---------------------------------------------------------------- [e10] TransactionExecutionListener

@Component
class CountingTxListener : TransactionExecutionListener {
    val events = CopyOnWriteArrayList<String>()

    private fun short(tx: TransactionExecution): String =
        tx.transactionName.split('.').takeLast(2).joinToString(".").ifEmpty { "(no name)" }

    override fun afterBegin(transaction: TransactionExecution, beginFailure: Throwable?) {
        events += "begin(${short(transaction)})"
    }

    override fun afterCommit(transaction: TransactionExecution, commitFailure: Throwable?) {
        events += "commit(${short(transaction)})"
    }

    override fun afterRollback(transaction: TransactionExecution, rollbackFailure: Throwable?) {
        events += "rollback(${short(transaction)})"
    }
}

// ---------------------------------------------------------------- [e12] readOnly 라우팅

/** 지금 Thread 에 등록된 readOnly 표시로 대상 DB 를 고른다. */
class ReadOnlyRoutingDataSource : AbstractRoutingDataSource() {
    override fun determineCurrentLookupKey(): Any =
        if (TSM.isCurrentTransactionReadOnly()) "reader" else "writer"
}

private fun h2(name: String): DataSource = HikariDataSource().apply {
    jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"
    driverClassName = "org.h2.Driver"
    username = "sa"
    poolName = name
}

private fun routing(): DataSource {
    val writer = h2("writer")
    return ReadOnlyRoutingDataSource().apply {
        setTargetDataSources(mapOf<Any, Any>("writer" to writer, "reader" to h2("reader")))
        setDefaultTargetDataSource(writer)
        afterPropertiesSet()
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("routing-a")
class RoutingDirectConfig {
    /** (a) AbstractRoutingDataSource 를 그대로 TransactionManager 에 준다. */
    @Bean
    @Primary
    fun dataSource(): DataSource = routing()
}

@Configuration(proxyBeanMethods = false)
@Profile("routing-b")
class RoutingLazyConfig {
    /** (b) 같은 라우팅 DataSource 를 LazyConnectionDataSourceProxy 로 감싼다. */
    @Bean
    @Primary
    fun dataSource(): DataSource = LazyConnectionDataSourceProxy(routing())
}

@Configuration(proxyBeanMethods = false)
@Profile("routing-c")
class RoutingReadOnlyDataSourceConfig {
    /** (c) 6.1.2+ setReadOnlyDataSource: 라우팅 없이 Lazy 프록시가 readOnly 커넥션을 reader 에서 받는다. */
    @Bean
    @Primary
    fun dataSource(): DataSource = LazyConnectionDataSourceProxy(h2("writer")).apply {
        setReadOnlyDataSource(h2("reader"))
    }
}

@Service
@Profile("routing")
class RoutingProbe(private val jdbc: JdbcTemplate) {
    @Transactional(readOnly = true)
    fun readOnlyDb(): String =
        "db=" + jdbc.queryForObject("select DATABASE()", String::class.java) +
            " readOnlyFlag=" + TSM.isCurrentTransactionReadOnly()

    @Transactional
    fun writeDb(): String =
        "db=" + jdbc.queryForObject("select DATABASE()", String::class.java) +
            " readOnlyFlag=" + TSM.isCurrentTransactionReadOnly()
}
