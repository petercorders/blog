package com.petercoders.blog.kcolab

import org.springframework.boot.SpringBootVersion
import org.springframework.core.SpringVersion

// JAVA-KOTLIN-CONCURRENCY 2편(Kotlin Coroutine) Spring 실험 러너.
//   ./gradlew --offline -q kcoLab --args='E14'          (kotlinx-coroutines-reactor 포함)
//   ./gradlew --offline -q kcoLabNoReactor --args='E15' (같은 코드, 런타임에서 kotlinx-coroutines-reactor 제외)
fun jarOf(className: String): String = try {
    Class.forName(className).protectionDomain.codeSource.location.path.substringAfterLast('/')
} catch (e: ClassNotFoundException) { "(없음)" }

fun envLine(): String =
    "# runtime: java ${System.getProperty("java.runtime.version")} (${System.getProperty("java.vendor")}) | Spring Boot ${SpringBootVersion.getVersion()} | Spring Framework ${SpringVersion.getVersion()}" +
    " | ${jarOf("kotlinx.coroutines.Job")} | reactor bridge: ${jarOf("kotlinx.coroutines.reactor.MonoKt")}" +
    " | ${jarOf("com.zaxxer.hikari.HikariDataSource")} | ${jarOf("io.micrometer.core.instrument.MeterRegistry")} | ${jarOf("kotlin.KotlinVersion")}"

fun main(args: Array<String>) {
    println(envLine())
    when (val id = args.firstOrNull()) {
        "E14" -> com.petercoders.blog.kcolab.e14.run()
        "E14NR" -> com.petercoders.blog.kcolab.e14.runNoReactorCheck()
        "E15" -> com.petercoders.blog.kcolab.e15.run()
        "E18" -> com.petercoders.blog.kcolab.e18.run()
        "E25" -> com.petercoders.blog.kcolab.e25.run()
        "E26" -> com.petercoders.blog.kcolab.e26.run()
        else -> println("unknown experiment: $id (E14 | E14NR | E15 | E18 | E25 | E26)")
    }
    System.exit(0)
}
