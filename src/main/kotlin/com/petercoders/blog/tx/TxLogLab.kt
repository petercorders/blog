package com.petercoders.blog.tx

import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import kotlin.system.exitProcess

// SPRING-INTERNALS 1편 [e8] — Transaction 로그가 어느 logger 에 찍히나, 부팅 CGLIB 경고가 root 레벨에 따라 사라지나.
// CglibAopProxy.validatedClasses 가 static 이라 같은 JVM 에서 두 번째 부팅은 클래스를 다시 검사하지 않는다.
// 그래서 레벨 비교는 모드마다 JVM 을 따로 띄운다.
//   ./gradlew --offline -q txLogLab --args=TRACE   # [e8]  interceptor TRACE + TransactionManager DEBUG
//   ./gradlew --offline -q txLogLab --args=INFO    # [e8b] root=INFO (CglibAopProxy 미지정)
//   ./gradlew --offline -q txLogLab --args=WARN    # [e8b] root=WARN (CglibAopProxy 미지정)
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "TRACE"
    val props = mutableListOf(
        "spring.main.web-application-type=none",
        "spring.main.banner-mode=off",
        "spring.datasource.url=jdbc:h2:mem:txlog;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.main.allow-circular-references=true",
        "spring.profiles.active=loglab",
    )
    when (mode) {
        "TRACE" -> props += listOf(
            "logging.level.root=INFO",
            "logging.level.org.springframework.transaction.interceptor=TRACE",
            // Boot 4.1.1 JDBC 자동 구성의 TransactionManager. APTM 의 logger 는 getClass() 라 이 이름이어야 DEBUG 가 찍힌다.
            "logging.level.org.springframework.jdbc.support.JdbcTransactionManager=DEBUG",
            "logging.level.org.springframework.jdbc.datasource.DataSourceUtils=DEBUG",
        )
        "INFO" -> props += "logging.level.root=INFO"
        "WARN" -> props += "logging.level.root=WARN"
        else -> error("mode must be TRACE, INFO or WARN")
    }
    println("## [e8${if (mode == "TRACE") "" else "b"}] txLogLab mode=$mode properties=${props.filter { it.startsWith("logging") }}")
    val ctx = SpringApplicationBuilder(TxLabApp::class.java).properties(*props.toTypedArray()).run()
    println("----- booted")
    if (mode == "TRACE") {
        println("transactionManager class = ${ctx.getBean(PlatformTransactionManager::class.java).javaClass.name}")
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        jdbc.execute("create table tx_log(id identity primary key, tag varchar(40), note varchar(40))")
        val p = ctx.getBean(BypassProbe::class.java)
        val n = ctx.getBean(NestedProbe::class.java)
        println("----- call: p.tx()  (external call through the proxy)")
        p.tx()
        println("----- call: p.selfInvoke()  (this.tx() inside the target)")
        p.selfInvoke()
        println("----- call: n.outerViaProxy()  (REQUIRES_NEW inner throws, outer catches)")
        n.outerViaProxy()
        println("----- call: n.outerRequiredViaProxy()  (REQUIRED inner throws, outer catches)")
        try { n.outerRequiredViaProxy() } catch (e: Throwable) { println("caller got ${e.javaClass.name}") }
        println("----- end")
    }
    ctx.close()
    exitProcess(0)
}
