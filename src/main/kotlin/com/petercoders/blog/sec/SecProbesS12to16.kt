// SPRING-INTERNALS 2편 — 프로필로 격리한 마커들.
// [S12] finalbean: stereotype 없는 Kotlin 클래스(= final)를 @Bean 으로 등록하고 메서드에 @PreAuthorize.
// [S14] retry: @Retryable(maxRetries = 2) + @Transactional + @PostAuthorize(거부) — 거부마다 commit된 쓰기가 반복되나.
// [S15] events: AuthorizationEventPublisher Bean 이 없을 때/있을 때, 무엇이 이벤트로 나오나.
// [S16] s16: 두 인터페이스에 서로 다른 @PreAuthorize 를 붙이고 둘 다 구현하면.
// 실행: ./gradlew --offline -q secLab --args='S12' / 'S14' / 'S15' / 'S16'
package com.petercoders.blog.sec

import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.resilience.annotation.EnableResilientMethods
import org.springframework.resilience.annotation.Retryable
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.access.prepost.PostFilter
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authorization.AuthorizationEventPublisher
import org.springframework.security.authorization.SpringAuthorizationEventPublisher
import org.springframework.security.authorization.event.AuthorizationEvent
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------- S12
/** stereotype 이 없어 kotlin-spring 이 열지 않는다(클래스·메서드 모두 final). */
class FinalSecuredService(private val jdbc: JdbcTemplate) {
    @PreAuthorize("hasRole('ADMIN')")
    fun secured(tag: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", tag)
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("finalbean")
class FinalBeanConfig {
    @Bean
    fun finalSecuredService(jdbc: JdbcTemplate) = FinalSecuredService(jdbc)
}

fun runS12() {
    println("## [S12] @Bean 으로 등록한 final Kotlin 클래스 + 메서드 @PreAuthorize — 기동되나")
    println("FinalSecuredService is final = ${java.lang.reflect.Modifier.isFinal(FinalSecuredService::class.java.modifiers)}")
    val ctx = try {
        bootSec("seclab-s12", "finalbean")
    } catch (e: Throwable) {
        println("startup failed: ${e.javaClass.name}")
        println("  message first line: ${e.message?.lineSequence()?.firstOrNull()}")
        var c: Throwable? = e.cause
        var depth = 1
        while (c != null && depth <= 6) {
            println("  cause[$depth]: ${c.javaClass.name}: ${c.message?.lineSequence()?.firstOrNull()}")
            c = c.cause; depth++
        }
        return
    }
    println("startup ok (unexpected)")
    ctx.close()
}

// ---------------------------------------------------------------- S14
@Configuration(proxyBeanMethods = false)
@Profile("retry")
@EnableResilientMethods
class RetryConfig

@Service
@Profile("retry")
class RetryProbe(private val jdbc: JdbcTemplate) {
    val invocations = AtomicInteger()

    @Retryable(maxRetries = 2, delay = 10)
    @Transactional
    @PostAuthorize("returnObject.ownerId == authentication.name")
    fun withdraw(tag: String, ownerId: String): Account {
        invocations.incrementAndGet()
        jdbc.update("insert into sec_log(tag, note) values (?, 'write-before-check')", tag)
        return Account(ownerId)
    }
}

fun runS14() {
    println("## [S14] @Retryable(maxRetries = 2) + @Transactional + @PostAuthorize(거부) — 기본 order")
    val ctx = bootSec("seclab-s14", "retry")
    try {
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        ensureSecLog(jdbc)
        println("internalAutoProxyCreator = ${ctx.getBean("org.springframework.aop.config.internalAutoProxyCreator").javaClass.name}")
        val p = ctx.getBean(RetryProbe::class.java)
        printChain("RetryProbe", p, ctx)
        authAs("alice", "ROLE_USER")
        val thrown = thrownOf { p.withdraw("s14", "bob") }
        println("withdraw(\"s14\", ownerId=\"bob\") as alice -> thrown=$thrown")
        println("body invocations = ${p.invocations.get()}, sec_log rows where tag='s14' = ${secRows(jdbc, "s14")}")
    } finally {
        ctx.close()
    }
}

// ---------------------------------------------------------------- S15
@Configuration(proxyBeanMethods = false)
@Profile("events")
class EventsConfig {
    @Bean
    fun authorizationEventPublisher(publisher: ApplicationEventPublisher) = SpringAuthorizationEventPublisher(publisher)
}

@Component
@Profile("events")
class AuthzEventRecorder {
    val events = CopyOnWriteArrayList<String>()

    @EventListener
    fun on(e: AuthorizationEvent) {
        events += "${e.javaClass.simpleName}(isGranted=${e.authorizationResult?.isGranted})"
    }
}

@Service
class EventFilterProbe {
    @PostFilter("filterObject != 'drop'")
    fun withDrop(): MutableList<String> = mutableListOf("keep", "drop")
}

fun runS15() {
    println("## [S15] 거부 이벤트 — AuthorizationEventPublisher Bean 유무")
    println()
    val c0 = bootSec("seclab-s15-default")
    try {
        println("(1) default context: AuthorizationEventPublisher beans = ${c0.getBeanNamesForType(AuthorizationEventPublisher::class.java).toList()}")
    } finally {
        c0.close()
    }
    // 같은 확인을 서블릿 웹 앱(Tomcat, server.port=0)으로도 한다. Boot 의 Security 자동 설정이 웹에서만 도는 부분이 있어서다.
    val cw = bootSec("seclab-s15-web", "", "spring.main.web-application-type=servlet", "server.port=0", "spring.security.user.password=s15-fixed") // 생성 비밀번호 로그를 막으려고 고정값
    try {
        println("(1b) servlet web context(server.port=0): AuthorizationEventPublisher beans = ${cw.getBeanNamesForType(AuthorizationEventPublisher::class.java).toList()}" +
            ", SecurityFilterChain beans = ${cw.getBeanNamesForType(org.springframework.security.web.SecurityFilterChain::class.java).toList()}")
    } finally {
        cw.close()
    }
    println()
    val ctx = bootSec("seclab-s15-events", "events")
    try {
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        ensureSecLog(jdbc)
        println("(2) events context: AuthorizationEventPublisher beans = ${ctx.getBeanNamesForType(AuthorizationEventPublisher::class.java).toList()}")
        val rec = ctx.getBean(AuthzEventRecorder::class.java)
        authAs("alice", "ROLE_ADMIN")
        val calls: List<Pair<String, () -> Unit>> = listOf(
            "@PreAuthorize deny (DenialProbe.denied, hasRole('NOPE'))" to { ctx.getBean(DenialProbe::class.java).denied(); Unit },
            "@PostAuthorize deny (OrderProbe.withdraw bob)" to { ctx.getBean(OrderProbe::class.java).withdraw("s15-post", "bob"); Unit },
            "@PostFilter removes 1 of 2 (EventFilterProbe.withDrop)" to { ctx.getBean(EventFilterProbe::class.java).withDrop(); Unit },
            "@PreAuthorize pass (SecureProbe.secured as ADMIN)" to { ctx.getBean(SecureProbe::class.java).secured("s15-pass") },
        )
        for ((label, call) in calls) {
            rec.events.clear()
            val t = thrownOf { call() }
            println("    $label -> thrown=${t.substringAfterLast('.')} events=${rec.events.size} ${rec.events}")
        }
        println()
        val pub = ctx.getBean(SpringAuthorizationEventPublisher::class.java)
        pub.setShouldPublishResult { true }
        println("(3) after setShouldPublishResult { true }:")
        rec.events.clear()
        ctx.getBean(SecureProbe::class.java).secured("s15-pass2")
        println("    @PreAuthorize pass -> events=${rec.events.size} ${rec.events}")
    } finally {
        ctx.close()
    }
}

// ---------------------------------------------------------------- S16
interface S16Admin {
    @PreAuthorize("hasRole('ADMIN')")
    fun op(tag: String): String
}

interface S16User {
    @PreAuthorize("hasRole('USER')")
    fun op(tag: String): String
}

@Service
@Profile("s16")
class S16Impl : S16Admin, S16User {
    override fun op(tag: String): String = "body-ran"
}

fun runS16() {
    println("## [S16] 두 인터페이스에 서로 다른 @PreAuthorize, 한 클래스가 둘 다 구현")
    val ctx = try {
        bootSec("seclab-s16", "s16")
    } catch (e: Throwable) {
        println("startup failed: ${e.javaClass.name}: ${e.message?.lineSequence()?.firstOrNull()}")
        return
    }
    try {
        println("startup ok")
        authAs("alice", "ROLE_ADMIN", "ROLE_USER")
        val b = ctx.getBean(S16Impl::class.java)
        try {
            println("first call op() -> ${b.op("s16")}")
        } catch (e: Throwable) {
            println("first call op() threw ${e.javaClass.name}")
            println("  message first line: ${e.message?.lineSequence()?.firstOrNull()}")
        }
    } finally {
        ctx.close()
    }
}
