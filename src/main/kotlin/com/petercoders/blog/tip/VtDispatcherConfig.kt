import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.SpringVersion
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// tip-p28 — 3편 코드 절 「Dispatcher와 Executor는 프로세스에 하나씩 만들고 닫는다」의 요지 코드 VtDispatcherConfig 를 그대로 띄운다.
// kotlin-spring(allopen) 플러그인 없이 kotlinc 로 컴파일하므로 Kotlin 클래스는 final 이다.
//   PLAIN : @Configuration 만 붙인 판 → Spring 7.0.9 가 기동을 거부하는가
//   FIXED : 본문 판 @Configuration(proxyBeanMethods = false) → 기동하고, Bean 위 Coroutine 이 Virtual Thread 에서 돌고, close 때 Executor 가 닫히는가
//   "$KC" -cp "$CO:$SC" VtDispatcherConfig.kt -include-runtime -d vtdc.jar && $J26/bin/java -cp "vtdc.jar:$CO:$SC" VtDispatcherConfigKt
//   SC = spring-context·beans·core·aop·expression 7.0.9 + micrometer-observation·commons 1.17.1 + commons-logging 1.3.5 + jspecify 1.0.0

// ---- 본문 요지 코드(그대로) ----
@Configuration(proxyBeanMethods = false)
class VtDispatcherConfig {
    @Bean(destroyMethod = "close")
    fun vtDispatcher(): ExecutorCoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
}
// ---- 여기까지 ----

// 대조: proxyBeanMethods 를 기본값(true)으로 둔 판
@Configuration
class VtDispatcherConfigPlain {
    @Bean(destroyMethod = "close")
    fun vtDispatcher(): ExecutorCoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
}

fun main() {
    println("# runtime: java ${System.getProperty("java.runtime.version")} | Spring Framework ${SpringVersion.getVersion()} | VtDispatcherConfig final=${java.lang.reflect.Modifier.isFinal(VtDispatcherConfig::class.java.modifiers)}")
    try {
        AnnotationConfigApplicationContext(VtDispatcherConfigPlain::class.java).close()
        println("PLAIN : started")
    } catch (e: Exception) {
        var c: Throwable? = e
        while (c?.cause != null && c.cause !== c) c = c.cause
        println("PLAIN : refused ${e.javaClass.simpleName} / root ${c?.javaClass?.simpleName}: ${c?.message}")
    }
    val ctx = AnnotationConfigApplicationContext(VtDispatcherConfig::class.java)
    val d = ctx.getBean(ExecutorCoroutineDispatcher::class.java)
    val onVt = runBlocking { withContext(d) { Thread.currentThread().isVirtual } }
    val executor = d.executor as ExecutorService
    println("FIXED : started, bean=${d.javaClass.simpleName}, withContext(bean) isVirtual=$onVt, executor.isShutdown before close=${executor.isShutdown}")
    ctx.close()
    println("FIXED : after ctx.close() executor.isShutdown=${executor.isShutdown} isTerminated=${executor.isTerminated}")
}
