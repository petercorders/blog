package com.petercoders.blog.txorder

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.aop.framework.Advised
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager as TSM
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

// SPRING-INTERNALS 1편 [e9] — @Transactional 과 직접 만든 @Aspect(분산 락 대역)가 한 메서드에 걸릴 때의 순서.
// spring-boot-starter-aspectj 는 이 태스크의 classpath 에만 있다(build.gradle.kts 의 txOrder configuration).
// 공용 classpath 에 넣으면 다른 랩의 자동 프록시 생성기가 바뀐다.
//   ./gradlew --offline -q txOrderLab
// 변형(프로필): (a) Aspect @Order 없음, (b) Aspect @Order(LOWEST_PRECEDENCE - 1),
//              (c) @EnableTransactionManagement(order = LOWEST_PRECEDENCE - 1) + Aspect @Order(LOWEST_PRECEDENCE)

@SpringBootApplication(scanBasePackages = ["com.petercoders.blog.txorder"])
class TxOrderApp

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LockLike

object Trace {
    val events = CopyOnWriteArrayList<String>()
}

/** 분산 락 Aspect 의 대역. 진입이 '락 획득', finally 가 '락 해제'다. */
abstract class LockLikeAspectBase {
    protected fun around(pjp: ProceedingJoinPoint): Any? {
        Trace.events += "lock acquire (isActualTransactionActive=${TSM.isActualTransactionActive()})"
        try {
            return pjp.proceed()
        } finally {
            Trace.events += "lock release (isActualTransactionActive=${TSM.isActualTransactionActive()})"
        }
    }
}

@Aspect
@Component
@Profile("a")
class LockLikeAspectNoOrder : LockLikeAspectBase() {
    @Around("@annotation(com.petercoders.blog.txorder.LockLike)")
    fun lock(pjp: ProceedingJoinPoint): Any? = around(pjp)
}

@Aspect
@Component
@Profile("b")
@Order(Ordered.LOWEST_PRECEDENCE - 1)
class LockLikeAspectOuter : LockLikeAspectBase() {
    @Around("@annotation(com.petercoders.blog.txorder.LockLike)")
    fun lock(pjp: ProceedingJoinPoint): Any? = around(pjp)
}

@Aspect
@Component
@Profile("c")
@Order(Ordered.LOWEST_PRECEDENCE)
class LockLikeAspectLowest : LockLikeAspectBase() {
    @Around("@annotation(com.petercoders.blog.txorder.LockLike)")
    fun lock(pjp: ProceedingJoinPoint): Any? = around(pjp)
}

@Configuration(proxyBeanMethods = false)
@Profile("c")
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)
class TxOuterConfig

@Service
@Profile("txorder")
class CouponService {
    @LockLike
    @Transactional
    fun issue() {
        Trace.events += "target body  (isActualTransactionActive=${TSM.isActualTransactionActive()})"
        TSM.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                Trace.events += "afterCommit"
            }
        })
    }
}

private fun orderName(o: Int?): String = when (o) {
    null -> "(not Ordered)"
    Ordered.LOWEST_PRECEDENCE -> "LOWEST_PRECEDENCE"
    Ordered.LOWEST_PRECEDENCE - 1 -> "LOWEST_PRECEDENCE - 1"
    Ordered.HIGHEST_PRECEDENCE -> "HIGHEST_PRECEDENCE"
    Ordered.HIGHEST_PRECEDENCE + 1 -> "HIGHEST_PRECEDENCE + 1"
    else -> o.toString()
}

fun main() {
    println("## [e9] advisor order: @Transactional + custom @Aspect on the same method (spring-boot-starter-aspectj on this classpath only)")
    println("aspectjweaver = ${org.aspectj.weaver.Advice::class.java.`package`.implementationVersion}")
    for ((profile, label) in listOf(
        "a" to "(a) Aspect without @Order, transaction advisor default order",
        "b" to "(b) Aspect @Order(LOWEST_PRECEDENCE - 1), transaction advisor default order",
        "c" to "(c) @EnableTransactionManagement(order = LOWEST_PRECEDENCE - 1) + Aspect @Order(LOWEST_PRECEDENCE)",
    )) {
        val ctx = SpringApplicationBuilder(TxOrderApp::class.java).properties(
            "spring.main.web-application-type=none",
            "spring.main.banner-mode=off",
            "spring.datasource.url=jdbc:h2:mem:txorder;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "logging.level.root=WARN",
            "spring.profiles.active=txorder,$profile",
        ).run()
        println()
        println("### [e9] $label")
        println("internalAutoProxyCreator = " +
            ctx.getBean("org.springframework.aop.config.internalAutoProxyCreator").javaClass.name)
        val svc = ctx.getBean(CouponService::class.java)
        println("advisors on the CouponService proxy (outermost first):")
        (svc as Advised).advisors.forEachIndexed { i, a ->
            val cls = a.javaClass.simpleName.ifEmpty { a.javaClass.name.substringAfterLast('.') }
            println("  [$i] $cls order=${orderName((a as? Ordered)?.order)} advice=${a.advice.javaClass.simpleName}")
        }
        Trace.events.clear()
        svc.issue()
        println("sequence:")
        Trace.events.forEach { println("  $it") }
        val release = Trace.events.indexOfFirst { it.startsWith("lock release") }
        val commit = Trace.events.indexOf("afterCommit")
        println("=> ${if (commit < release) "commit before lock release" else "lock release before commit"}")
        ctx.close()
    }
    exitProcess(0)
}
