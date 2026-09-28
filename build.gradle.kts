plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.petercoders"
version = "0.0.1-SNAPSHOT"
description = "blog"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-web")   // SPRING-INTERNALS 3편(예외 처리) 실험용
    runtimeOnly("com.h2database:h2")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// kco/ tip/ jkc/ 는 kotlinc 로 단독 컴파일하는 코루틴 실험 파일이라 Gradle 클래스패스에
// kotlinx-coroutines 가 없다. Gradle 컴파일 대상에서 뺀다(이전부터 컴파일 안 되던 상태).
sourceSets.main {
    kotlin.exclude(
        "com/petercoders/blog/kco/**",
        "com/petercoders/blog/tip/**",
        "com/petercoders/blog/jkc/**",
    )
}

// gc/ 아래 실험 파일들이 각자 main 을 갖고 있어 bootJar 가 진입점을 못 고른다. 명시해 둔다.
springBoot {
    mainClass.set("com.petercoders.blog.BlogApplicationKt")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// SPRING-INTERNALS 1편(@Transactional 프록시) 실험 러너. ./gradlew --offline -q txLab
tasks.register<JavaExec>("txLab") {
    group = "blog"
    description = "@Transactional 프록시 실험 전체 실행"
    mainClass.set("com.petercoders.blog.tx.TxLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
}

// SPRING-INTERNALS 3편(시큐리티/트랜잭션 어드바이저 순서) 실험 러너.
// ./gradlew --offline -q secLab --args='S3'
tasks.register<JavaExec>("secLab") {
    group = "blog"
    description = "시큐리티·트랜잭션 어드바이저 order/진입순서 실험"
    mainClass.set("com.petercoders.blog.sec.SecLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
}

// SPRING-INTERNALS 3편(예외 처리) 실험 러너. ./gradlew --offline -q excLab
tasks.register<JavaExec>("excLab") {
    group = "blog"
    description = "@RestControllerAdvice / ProblemDetail 예외 처리 실험 전체 실행"
    mainClass.set("com.petercoders.blog.exc.ExcLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
}

// SPRING-INTERNALS 4편(프로퍼티 우선순위) 실험 러너. ./gradlew --offline -q propLab
tasks.register<JavaExec>("propLab") {
    group = "blog"
    description = "PropertySource 우선순위와 relaxed binding 실험"
    mainClass.set("com.petercoders.blog.prop.PropLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
}

// SPRING-INTERNALS 5편(@Async·@Scheduled) 실험 러너. ./gradlew --offline -q asyncLab
tasks.register<JavaExec>("asyncLab") {
    group = "blog"
    description = "@Async·@Scheduled 기본값과 예외 계약 실험"
    mainClass.set("com.petercoders.blog.async.AsyncLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
}
