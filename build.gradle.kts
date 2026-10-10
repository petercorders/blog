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

// SPRING-INTERNALS 2편(Method Security) 실험 러너.
// ./gradlew --offline -q secLab --args='S3'
tasks.register<JavaExec>("secLab") {
    group = "blog"
    description = "Method Security 실험(Advisor order, 우회 경로, 거부, 스레드 경계)"
    mainClass.set("com.petercoders.blog.sec.SecLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
}

// SPRING-INTERNALS 2편 전용 추가 의존. main 의 implementation/runtime classpath 에는 넣지 않는다.
// 5편 A2e 가 'context-propagation 없음'을 전제로 재고, 다른 lab 도 main runtimeClasspath 를 쓰기 때문이다.
//  - io.micrometer:context-propagation          : [S8g] spring.task.execution.propagate-context
//  - spring-security-access                      : [S10] 옛 @EnableGlobalMethodSecurity 의 결정 API(Security 7.0 분리)
//  - kotlinx-coroutines-core                     : [S8d] Coroutine 디스패치 경계(컴파일은 compileOnly 로만)
// ./gradlew -q secLabExtra --args='S8g'
val secLabExtra: Configuration by configurations.creating
dependencies {
    secLabExtra("io.micrometer:context-propagation")
    secLabExtra("org.springframework.security:spring-security-access")
    secLabExtra("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core")             // [S8d] 컴파일용. 런타임에는 secLabExtra 태스크에만 있다
    testImplementation("org.springframework.security:spring-security-test") // [S13] @WithMockUser
}
sourceSets.main {
    // [S9] -parameters 없이 javac 로 따로 컴파일하는 Java 소스. Gradle(Boot 플러그인이 -parameters 를 건다)이 컴파일하지 않게 뺀다.
    java.exclude("com/petercoders/blog/sec/s9src/**")
    kotlin.exclude("com/petercoders/blog/sec/s9src/**")
}
tasks.register<JavaExec>("secLabExtra") {
    group = "blog"
    description = "Method Security 실험 중 secLabExtra 의존이 필요한 마커(S8g, S8d, S10)"
    mainClass.set("com.petercoders.blog.sec.SecLabKt")
    classpath = sourceSets["main"].runtimeClasspath + secLabExtra
    standardOutput = System.out
}

// SPRING-INTERNALS 3편(예외 처리) 전용 의존성. excLab 러너 클래스패스에만 들어가고
// main runtimeClasspath 에는 들어가지 않는다(2·4·5편 러너의 Bean·Filter 덤프를 바꾸지 않기 위해).
//   excLabExtra: validation(X10) · jackson-module-kotlin(X10 d) · spring-test MockMvc(X15)
//   excLabObs  : actuator + tracing(X14 선택 실험) — excLabObs 태스크에서만 쓴다
val excLabExtra: Configuration by configurations.creating {
    extendsFrom(configurations["implementation"], configurations["runtimeOnly"])
}
val excLabObs: Configuration by configurations.creating {
    extendsFrom(excLabExtra)
}
dependencies {
    excLabExtra("org.springframework.boot:spring-boot-starter-validation")
    excLabExtra("tools.jackson.module:jackson-module-kotlin")
    excLabExtra("org.springframework:spring-test")
    excLabObs("org.springframework.boot:spring-boot-starter-actuator")
    excLabObs("org.springframework.boot:spring-boot-micrometer-tracing-brave")
    excLabObs("io.micrometer:micrometer-tracing-bridge-brave")
    // 컴파일에만 필요하다(런타임에는 위 configuration 이 넣는다).
    compileOnly("jakarta.validation:jakarta.validation-api")
    compileOnly("org.springframework:spring-test")
    compileOnly("io.micrometer:micrometer-core")
}

// SPRING-INTERNALS 3편(예외 처리) 실험 러너. ./gradlew --offline -q excLab --args='X10'
tasks.register<JavaExec>("excLab") {
    group = "blog"
    description = "@RestControllerAdvice / ProblemDetail 예외 처리 실험 전체 실행"
    mainClass.set("com.petercoders.blog.exc.ExcLabKt")
    classpath = sourceSets["main"].output + excLabExtra
    standardOutput = System.out
}

// 3편 X14 선택 실험(actuator 메트릭·tracing). ./gradlew --offline -q excLabObs --args='X14OBS'
tasks.register<JavaExec>("excLabObs") {
    group = "blog"
    description = "3편 X14 선택 실험: actuator·tracing 이 클래스패스에 있을 때"
    mainClass.set("com.petercoders.blog.exc.ExcLabKt")
    classpath = sourceSets["main"].output + excLabObs
    standardOutput = System.out
}

// SPRING-INTERNALS 4편(프로퍼티 우선순위) 실험 러너. ./gradlew --offline -q propLab
// -PpropJvm='-Dkey=value ...' 는 실행 JVM 에 진짜 -D 로 넘긴다([P4] 서열 실측용. 기본은 비어 있다).
tasks.register<JavaExec>("propLab") {
    group = "blog"
    description = "PropertySource 우선순위와 relaxed binding 실험"
    mainClass.set("com.petercoders.blog.prop.PropLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
    (project.findProperty("propJvm") as String?)?.let { jvmArgs(it.split(' ').filter { a -> a.isNotBlank() }) }
}

// SPRING-INTERNALS 4편 전용 추가 의존. main 의 implementation/runtime classpath 에는 넣지 않는다
// (actuator 가 main 에 들어오면 3편 덤프, validation·migrator 가 들어오면 다른 lab 의 기동 전제가 바뀐다).
//  - spring-boot-starter-actuator     : [P13] /actuator/env · /configprops 실제 HTTP
//  - spring-boot-starter-validation   : [P15a] @Validated + @NotBlank
//  - spring-boot-properties-migrator  : [P15c] deprecated 키 리포트·런타임 이관
//   ./gradlew --offline -q propLabActuator --args='P13'
//   ./gradlew --offline -q propLabExtra --args='P15'
val propLabExtra: Configuration by configurations.creating
dependencies {
    propLabExtra("org.springframework.boot:spring-boot-starter-actuator")
    propLabExtra("org.springframework.boot:spring-boot-starter-validation")
    propLabExtra("org.springframework.boot:spring-boot-properties-migrator")
}
tasks.register<JavaExec>("propLabActuator") {
    group = "blog"
    description = "4편 [P13] actuator env/configprops 를 server.port=0 웹 서버에 curl 로"
    mainClass.set("com.petercoders.blog.prop.PropLabKt")
    classpath = sourceSets["main"].runtimeClasspath + propLabExtra
    standardOutput = System.out
}
tasks.register<JavaExec>("propLabExtra") {
    group = "blog"
    description = "4편 [P15] @Validated · ignoreUnknownFields · properties-migrator"
    mainClass.set("com.petercoders.blog.prop.PropLabKt")
    classpath = sourceSets["main"].runtimeClasspath + propLabExtra
    standardOutput = System.out
}
// [P4b] jar 밖(file:./, ./config/) 대 jar 안(classpath) 실측. workingDir 를 build/proplab-cwd 로 고정한다.
//   ./gradlew --offline -q propLabCwd --args='P4B'
tasks.register<JavaExec>("propLabCwd") {
    group = "blog"
    description = "4편 [P4b] workingDir 를 고정하고 기본 위치 file:./ 와 classpath 를 비교"
    mainClass.set("com.petercoders.blog.prop.PropLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
    val cwd = layout.buildDirectory.dir("proplab-cwd")
    workingDir(cwd)
    doFirst { cwd.get().asFile.mkdirs() }
}
// [P12] 테스트 소스 우선순위. 공용 test 설정은 그대로 두고 전용 Test 태스크에서만 진짜 -D 와 OS env 를 넣는다.
//   ./gradlew --offline propTestLab --console=plain
tasks.register<Test>("propTestLab") {
    group = "blog"
    description = "4편 [P12] @SpringBootTest/@TestPropertySource/@DynamicPropertySource 우선순위"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.petercoders.blog.prop.PropTestLab*") }
    systemProperty("prop.lab.value", "fromSystemProperty")
    environment("PROP_LAB_VALUE", "fromOsEnv")
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    outputs.upToDateWhen { false }
}

// SPRING-INTERNALS 5편(@Async·@Scheduled) 실험 러너. ./gradlew --offline -q asyncLab
tasks.register<JavaExec>("asyncLab") {
    group = "blog"
    description = "@Async·@Scheduled 기본값과 예외 계약 실험"
    mainClass.set("com.petercoders.blog.async.AsyncLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
}

// SPRING-INTERNALS 5편 전용 추가 의존. main 의 implementation/runtime classpath 에는 넣지 않는다.
// A2e 는 'context-propagation 없음'을 전제로 재고, 다른 lab 도 main runtimeClasspath 를 쓰기 때문이다.
//  - asyncLabCtx : io.micrometer:context-propagation  → [A2eCtx] spring.task.execution.propagate-context
//  - asyncLabObs : spring-boot-starter-actuator       → [A6] executor 메트릭 자동 바인딩
//   ./gradlew --offline -q asyncLabCtx --args='A2eCtx'
//   ./gradlew --offline -q asyncLabObs --args='A6'
//   ./gradlew --offline -q asyncLabClasspath          ([A4f] a4f_sigterm.py 가 java 를 직접 띄울 때 쓰는 클래스패스)
val asyncLabCtx: Configuration by configurations.creating
val asyncLabObs: Configuration by configurations.creating
dependencies {
    asyncLabCtx("io.micrometer:context-propagation")
    asyncLabObs("org.springframework.boot:spring-boot-starter-actuator")
    compileOnly("io.micrometer:context-propagation")   // [A2eCtx] ContextRegistry 컴파일용. 런타임에는 asyncLabCtx 태스크에만 있다
}
tasks.register<JavaExec>("asyncLabCtx") {
    group = "blog"
    description = "5편 A2eCtx: io.micrometer:context-propagation 이 클래스패스에 있을 때"
    mainClass.set("com.petercoders.blog.async.AsyncLabKt")
    classpath = sourceSets["main"].runtimeClasspath + asyncLabCtx
    standardOutput = System.out
}
tasks.register<JavaExec>("asyncLabObs") {
    group = "blog"
    description = "5편 A6: actuator 가 클래스패스에 있을 때 executor 메트릭"
    mainClass.set("com.petercoders.blog.async.AsyncLabKt")
    classpath = sourceSets["main"].runtimeClasspath + asyncLabObs
    standardOutput = System.out
}
tasks.register("asyncLabClasspath") {
    group = "blog"
    description = "5편 A4f: main runtimeClasspath 를 한 줄로 찍는다"
    val cp = sourceSets["main"].runtimeClasspath
    doLast { println(cp.asPath) }
}

// SPRING-INTERNALS 1편(@Transactional) 분리 실험. 다른 랩(sec·exc·async·prop)의 classpath 와
// 자동 프록시 생성기(APC)를 바꾸지 않도록, aspectj·coroutines 는 전용 configuration 에만 두고
// 해당 JavaExec 태스크 classpath 에만 더한다(main runtimeClasspath 는 그대로).
//  - txOrder     : spring-boot-starter-aspectj(Boot 4 이름, 3.x 의 spring-boot-starter-aop) → [e9] Advisor 순서
//  - txCo        : kotlinx-coroutines-core                                                   → [e11] Coroutine
//  - txCoReactor : kotlinx-coroutines-reactor(Spring 문서가 Coroutine 지원에 요구)            → [e11] 비교 변형
val txOrder: Configuration by configurations.creating
val txCo: Configuration by configurations.creating
val txCoReactor: Configuration by configurations.creating
dependencies {
    txOrder("org.springframework.boot:spring-boot-starter-aspectj")
    compileOnly("org.aspectj:aspectjweaver")                        // [e9] @Aspect 컴파일용. 런타임에는 txOrderLab 에만 있다
    txCo("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    txCoReactor("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
}

// [e8] Transaction 로그 logger·레벨, 부팅 CGLIB 경고. 모드마다 JVM 을 따로 띄운다.
// ./gradlew --offline -q txLogLab --args=TRACE|INFO|WARN
tasks.register<JavaExec>("txLogLab") {
    group = "blog"
    description = "@Transactional 로그(interceptor TRACE, TransactionManager DEBUG)와 CGLIB 경고 레벨 조건"
    mainClass.set("com.petercoders.blog.tx.TxLogLabKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardOutput = System.out
}

// [e9] @Transactional 과 커스텀 @Aspect 의 순서. ./gradlew --offline -q txOrderLab
tasks.register<JavaExec>("txOrderLab") {
    group = "blog"
    description = "Transaction Advisor 와 커스텀 @Aspect(락 대역)의 order 실험"
    mainClass.set("com.petercoders.blog.txorder.TxOrderLabKt")
    classpath = sourceSets["main"].runtimeClasspath + txOrder
    standardOutput = System.out
}

// [e11] JDBC TransactionManager + suspend fun @Transactional. ./gradlew --offline -q txCoLab / txCoLabReactor
tasks.register<JavaExec>("txCoLab") {
    group = "blog"
    description = "suspend fun @Transactional (kotlinx-coroutines-core only)"
    mainClass.set("com.petercoders.blog.txco.TxCoLabKt")
    classpath = sourceSets["main"].runtimeClasspath + txCo
    standardOutput = System.out
}
tasks.register<JavaExec>("txCoLabReactor") {
    group = "blog"
    description = "suspend fun @Transactional (+ kotlinx-coroutines-reactor)"
    mainClass.set("com.petercoders.blog.txco.TxCoLabKt")
    classpath = sourceSets["main"].runtimeClasspath + txCo + txCoReactor
    standardOutput = System.out
}

// JAVA-KOTLIN-CONCURRENCY 2편(Kotlin Coroutine) Spring 실험(kco-e14·e15·e18·e25·e26).
// main 의 implementation/runtime classpath 를 건드리지 않도록 전용 sourceSet "kcolab" 으로 분리한다.
// aspectjweaver(APC 승격)·webflux·coroutines-reactor 가 main 에 들어가면 tx·sec·async 실험의 전제가 깨지기 때문이다.
// 소스는 src/main/kotlin/com/petercoders/blog/kcolab/ 에 두고 main 컴파일에서는 뺀다.
//   ./gradlew --offline -q kcoLab --args='E14'          (kotlinx-coroutines-reactor 포함)
//   ./gradlew --offline -q kcoLabNoReactor --args='E15' (같은 코드, 런타임에서 kotlinx-coroutines-reactor 만 뺌)
sourceSets.main {
    kotlin.exclude("com/petercoders/blog/kcolab/**")
}
val kcolab: SourceSet by sourceSets.creating {
    kotlin.srcDir("src/main/kotlin")
    kotlin.include("com/petercoders/blog/kcolab/**")
}
dependencies {
    "kcolabImplementation"("org.springframework.boot:spring-boot-starter-web")
    "kcolabImplementation"("org.springframework.boot:spring-boot-starter-webflux")   // WebClient(awaitBody) 용. servlet 이 있으니 앱은 MVC 로 뜬다
    "kcolabImplementation"("org.springframework.boot:spring-boot-starter-jdbc")
    "kcolabImplementation"("org.springframework.boot:spring-boot-starter-aspectj")   // Boot 4 이름(3.x 의 starter-aop)
    "kcolabImplementation"("io.micrometer:micrometer-core")
    "kcolabImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    "kcolabImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    "kcolabImplementation"("org.jetbrains.kotlin:kotlin-reflect")
    "kcolabRuntimeOnly"("com.h2database:h2")
}
tasks.register<JavaExec>("kcoLab") {
    group = "blog"
    description = "Kotlin Coroutine 2편 Spring 실험(kotlinx-coroutines-reactor 포함)"
    mainClass.set("com.petercoders.blog.kcolab.KcoLabKt")
    classpath = kcolab.runtimeClasspath
    standardOutput = System.out
}
tasks.register<JavaExec>("kcoLabNoReactor") {
    group = "blog"
    description = "Kotlin Coroutine 2편 Spring 실험(런타임에서 kotlinx-coroutines-reactor 제외)"
    mainClass.set("com.petercoders.blog.kcolab.KcoLabKt")
    classpath = kcolab.runtimeClasspath.filter { !it.name.startsWith("kotlinx-coroutines-reactor") }
    standardOutput = System.out
}

// JAVA-KOTLIN-CONCURRENCY 3편(실무) Spring·HikariCP 실험(tip-p13·p14·p15·p20).
// kcolab 과 같은 방식으로 전용 sourceSet "tipSpring" 에 격리한다. main 의 implementation/runtime classpath 는 건드리지 않는다
// (context-propagation·coroutines-reactor 가 main 에 들어가면 async A2e·sec S8g 등 다른 글 실험의 전제가 깨진다).
// 소스는 src/main/kotlin/com/petercoders/blog/tip/spring/ (main 은 이미 tip/** 를 뺀다). 컴파일은 toolchain 25, 실행은 JDK 26.
//   ./gradlew --offline -q tipLab --args='E3 1' | 'E4 1' | 'E5' | 'E10 true'
//   ./gradlew --offline -q tipLabClasspath      (같은 클래스패스로 java 를 직접 띄울 때. 예: JDK 25 비교)
val tipSpring: SourceSet by sourceSets.creating {
    kotlin.srcDir("src/main/kotlin")
    kotlin.include("com/petercoders/blog/tip/spring/**")
}
dependencies {
    "tipSpringImplementation"("org.springframework.boot:spring-boot-starter-web")
    "tipSpringImplementation"("org.springframework.boot:spring-boot-starter-jdbc")
    "tipSpringImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core")      // Boot 4.1.1 BOM → 1.10.2 (kcolab 과 같다)
    "tipSpringImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")   // MVC suspend 핸들러에 필요(kco-e14 E14NR)
    "tipSpringImplementation"("org.jetbrains.kotlin:kotlin-reflect")
    "tipSpringImplementation"("com.h2database:h2")                                  // E4 는 org.h2.tools.Server(TCP 모드)를 쓴다
    "tipSpringRuntimeOnly"("io.micrometer:context-propagation")
}
tasks.register<JavaExec>("tipLab") {
    group = "blog"
    description = "3편 실무 Spring·HikariCP 실험(E3 풀 vs view, E4 JDBC 타임아웃, E5 RestClient, E10 VT suspend 핸들러)"
    mainClass.set("com.petercoders.blog.tip.spring.TipLabKt")
    classpath = tipSpring.runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(26)) })
    standardOutput = System.out
}
tasks.register("tipLabClasspath") {
    group = "blog"
    description = "3편 tipSpring runtimeClasspath 를 한 줄로 찍는다"
    val cp = tipSpring.runtimeClasspath
    doLast { println(cp.asPath) }
}
