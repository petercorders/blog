package com.petercoders.blog.tip.spring

import org.springframework.boot.SpringBootVersion
import org.springframework.core.SpringVersion

// JAVA-KOTLIN-CONCURRENCY 3편(실무) Spring·HikariCP 실험 러너. sourceSet tipSpring(build.gradle.kts).
//   ./gradlew --offline -q tipLab --args='E3 1'      (풀 vs view, 케이스마다 자식 JVM 을 새로 띄운다. 숫자는 반복 번호)
//   ./gradlew --offline -q tipLab --args='E4 1'      (JDBC 타임아웃 층, 숫자는 반복 번호. 1 이면 기준 시간도 찍는다)
//   ./gradlew --offline -q tipLab --args='E5'        (RestClient read timeout 과 withTimeout)
//   ./gradlew --offline -q tipLab --args='E10 true'  (spring.threads.virtual.enabled=true|false 의 suspend 핸들러 스레드)
fun jarOf(className: String): String = try {
    Class.forName(className).protectionDomain.codeSource.location.path.substringAfterLast('/')
} catch (e: ClassNotFoundException) { "(없음)" }

fun envLine(): String =
    "# runtime: java ${System.getProperty("java.runtime.version")} (${System.getProperty("java.vendor")}) | Spring Boot ${SpringBootVersion.getVersion()} | Spring Framework ${SpringVersion.getVersion()}" +
    " | ${jarOf("kotlinx.coroutines.Job")} | reactor bridge: ${jarOf("kotlinx.coroutines.reactor.MonoKt")}" +
    " | ${jarOf("com.zaxxer.hikari.HikariDataSource")} | ${jarOf("org.h2.Driver")} | context-propagation: ${jarOf("io.micrometer.context.ContextRegistry")} | ${jarOf("kotlin.KotlinVersion")}"

fun main(args: Array<String>) {
    if (args.firstOrNull() != "E3case") println(envLine())
    when (val id = args.firstOrNull()) {
        "E3" -> com.petercoders.blog.tip.spring.e3.forkAll(args.getOrNull(1) ?: "1")
        "E3case" -> com.petercoders.blog.tip.spring.e3.runCase(args[1])
        "E4" -> com.petercoders.blog.tip.spring.e4.run(args.drop(1).map { it.toInt() }.ifEmpty { listOf(1, 2, 3) })
        "E4cal" -> com.petercoders.blog.tip.spring.e4.calibrate()
        "E5" -> com.petercoders.blog.tip.spring.e5.run()
        "E10" -> com.petercoders.blog.tip.spring.e10.run(args.getOrNull(1)?.toBoolean() ?: false)
        else -> println("unknown experiment: $id (E3 <rep> | E4 | E4cal | E5 | E10 <true|false>)")
    }
    System.exit(0)
}
