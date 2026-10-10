package com.petercoders.blog.prop

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.DefaultResourceLoader
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// SPRING-INTERNALS 4편 재실측(2026-10) 프로브. 진입점은 PropLab.kt main 하나다.
//   P4  : P4' 클린 서열(진짜 OS env · 진짜 -D · 진짜 커맨드라인 · @PropertySource · configtree · defaults), 세 시점 덤프, P16b
//   P4B : jar 밖 application.yml 대 jar 안 application-dev.yml (propLabCwd 태스크, workingDir 고정)
//   P9  : 두 프로파일 순서, 멀티 문서 on-profile, P9b 프로파일 문서 안 활성화·옛 키 spring.profiles
//   P10 : location 콤마 순서, spring.config.location 대체, 같은 위치 .properties 대 .yml, P10c import 파일 없음
//   P11 : 리스트·Map·네 번째 후보(소문자 레거시)·리스트 원소 레거시 표기, numberOfElements
//   P14 : @Value 와 환경 변수(진짜 OS env), P14b 기본값 없는 @Value 의 PSPC 유무
//   P17 : @ConfigurationProperties 의 BindMethod(JAVA_BEAN / VALUE_OBJECT)
//   P18 : Kubernetes Service 변수 REDIS_PRIMARY_PORT=tcp://… 와 prefix 충돌

private const val LAB_KEY = "prop.lab.value"

/** refresh 중 @PostConstruct 덤프가 어느 기동인지 알려 주는 표식. */
internal object PropMarker {
    @Volatile var current: String = "P4"
    @Volatile var key: String = LAB_KEY
}

class RefreshProbe(private val env: ConfigurableEnvironment) {
    @PostConstruct
    fun init() {
        val m = "[${PropMarker.current}:refresh]"
        dump(m, env)
        holds(m, env, PropMarker.key)
    }
}

/** @PropertySource 는 refresh 중 설정 클래스 파싱 때 붙는다. 위치는 defaults 의 proplab.ps.file 이 정한다. */
@Configuration(proxyBeanMethods = false)
@PropertySource("file:\${proplab.ps.file}")
open class PsConfig {
    @Bean
    open fun refreshProbe(env: ConfigurableEnvironment) = RefreshProbe(env)
}

private fun preparedListener(tag: String, key: String) =
    object : ApplicationListener<ApplicationEnvironmentPreparedEvent> {
        override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
            val m = "[$tag:prepared]"
            dump(m, event.environment)
            holds(m, event.environment, key)
        }
    }

private fun builder(source: Class<*>): SpringApplicationBuilder =
    SpringApplicationBuilder(source)
        .web(WebApplicationType.NONE)
        .bannerMode(org.springframework.boot.Banner.Mode.OFF)

/** 기동 실패를 잡아서 찍는다. 실패 리포트(LoggingFailureAnalysisReporter)는 로그에 그대로 남는다. */
private fun tryRun(tag: String, b: SpringApplicationBuilder, args: Array<String>, block: (ConfigurableApplicationContext) -> Unit) {
    try {
        b.run(*args).use(block)
    } catch (t: Throwable) {
        failure("[$tag]", t)
    }
}

// ---------- P4': 클린 서열 ----------

internal fun runP4Clean(args: Array<String>) {
    println("[P4] OS PROP_LAB_VALUE=${System.getenv("PROP_LAB_VALUE") ?: "<not set>"}")
    println("[P4] -Dprop.lab.value=${System.getProperty(LAB_KEY) ?: "<not set>"} (JavaExec jvmArgs 로 넣은 진짜 -D)")
    println("[P4] command line args=${args.toList()}")
    val dir = freshDir("p4c")
    val ext = writeYml(dir, "external.yml", "prop:\n  lab:\n    value: fromImportFile")
    val tree = File(dir, "tree").apply { mkdirs() }
    File(tree, LAB_KEY).writeText("fromConfigTree")
    writeYml(
        dir, "application.yml",
        """
        spring:
          config:
            import: "optional:file:${ext.absolutePath},optional:configtree:${tree.absolutePath}/"
        prop:
          lab:
            value: fromApplicationYml
        """
    )
    writeYml(dir, "application-dev.yml", "prop:\n  lab:\n    value: fromProfileYml")
    val ps = File(dir, "ps.properties").apply { writeText("$LAB_KEY=fromPropertySourceAnnotation\n") }
    // defaults = builder.properties(...) → defaultProperties. 기동 전에 넣는다(옛 P4 의 기동 뒤 addLast 는 쓰지 않는다).
    val defaults = mapOf<String, Any>(
        LAB_KEY to "fromDefaults",
        "proplab.ps.file" to ps.absolutePath,
        "spring.config.additional-location" to "file:${dir.absolutePath}/",
    )

    fun bootP4(tag: String, initializer: Boolean) {
        PropMarker.current = tag
        PropMarker.key = LAB_KEY
        val b = builder(PsConfig::class.java).properties(defaults).listeners(preparedListener(tag, LAB_KEY))
        if (initializer) {
            b.initializers({ ctx: ConfigurableApplicationContext ->
                ctx.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource("proplab-systemEnvironment", mutableMapOf<String, Any>("PROP_LAB_VALUE" to "fromInitializerAddFirst"))
                )
            })
        }
        b.run(*args).use { ctx ->
            val env = ctx.environment as ConfigurableEnvironment
            dump("[$tag:started]", env)
            holds("[$tag:started]", env, LAB_KEY)
            println("[$tag:started] getProperty=${env.getProperty(LAB_KEY)} winner=${winner(env, LAB_KEY)}")
            println("[$tag:started] activeProfiles=${env.activeProfiles.toList()}")
        }
    }
    bootP4("P4-clean", initializer = false)
    bootP4("P4-initializer", initializer = true)

    // [P16b] @PropertySource 파일 안의 spring.profiles.active 는 프로파일 파일을 불러오는가
    val d2 = freshDir("p16b")
    writeYml(d2, "application.yml", "prop:\n  lab:\n    value: fromApplicationYml")
    writeYml(d2, "application-dev.yml", "prop:\n  lab:\n    value: fromProfileYml")
    val ps2 = File(d2, "ps.properties").apply { writeText("spring.profiles.active=dev\n") }
    PropMarker.current = "P16b"
    builder(PsConfig::class.java)
        .properties(mapOf<String, Any>("proplab.ps.file" to ps2.absolutePath, "spring.config.additional-location" to "file:${d2.absolutePath}/"))
        .run().use { ctx ->
            val env = ctx.environment as ConfigurableEnvironment
            dump("[P16b:started]", env)
            println("[P16b] spring.profiles.active property=${env.getProperty("spring.profiles.active")} from=${winner(env, "spring.profiles.active")}")
            println("[P16b] activeProfiles=${env.activeProfiles.toList()}")
            println("[P16b] application-dev.yml loaded=${env.propertySources.any { it.name.contains("application-dev.yml") }}")
            println("[P16b] prop.lab.value=${env.getProperty(LAB_KEY)} from=${winner(env, LAB_KEY)}")
        }
}

// ---------- P4b: jar 밖 대 jar 안 ----------

internal fun runP4b() {
    val cwd = File(".").canonicalFile
    println("[P4B] cwd=$cwd")
    val tmp = freshDir("p4b")
    val jar = File(tmp, "packaged.jar")
    JarOutputStream(jar.outputStream()).use { jos ->
        jos.putNextEntry(JarEntry("application.yml")); jos.write("prop:\n  lab:\n    value: fromPackagedYml\n".toByteArray()); jos.closeEntry()
        jos.putNextEntry(JarEntry("application-dev.yml")); jos.write("prop:\n  lab:\n    value: fromPackagedProfileYml\n".toByteArray()); jos.closeEntry()
    }
    println("[P4B] packaged jar=${jar.absolutePath} entries=[application.yml, application-dev.yml]")
    val loader = DefaultResourceLoader(URLClassLoader(arrayOf(jar.toURI().toURL()), PropLabConfig::class.java.classLoader))

    fun clean() {
        File(cwd, "application.yml").delete(); File(cwd, "application-dev.yml").delete()
        File(cwd, "config").deleteRecursively()
    }

    fun run(tag: String, props: Map<String, Any> = emptyMap(), setup: () -> Unit) {
        clean(); setup()
        val b = builder(PropLabConfig::class.java).resourceLoader(loader).properties(props)
        tryRun(tag, b, arrayOf("--spring.profiles.active=dev")) { ctx ->
            val env = ctx.environment as ConfigurableEnvironment
            dump("[$tag]", env)
            holds("[$tag]", env, LAB_KEY)
            println("[$tag] getProperty=${env.getProperty(LAB_KEY)} winner=${winner(env, LAB_KEY)}")
        }
        clean()
        println()
    }
    run("P4B-control(no external)") { }
    run("P4B-1 ./application.yml") { writeYml(cwd, "application.yml", "prop:\n  lab:\n    value: fromExternalYml") }
    run("P4B-2 ./config/application.yml") {
        writeYml(File(cwd, "config").apply { mkdirs() }, "application.yml", "prop:\n  lab:\n    value: fromExternalConfigDirYml")
    }
    run("P4B-3 ./application.yml + ./application-dev.yml") {
        writeYml(cwd, "application.yml", "prop:\n  lab:\n    value: fromExternalYml")
        writeYml(cwd, "application-dev.yml", "prop:\n  lab:\n    value: fromExternalProfileYml")
    }
    // 대안: 기본 위치와 같은 꼴을 spring.config.location 으로 직접 준다. 콤마=그룹 둘, 세미콜론=그룹 하나.
    val ext = freshDir("p4b-ext")
    writeYml(ext, "application.yml", "prop:\n  lab:\n    value: fromExternalYml")
    run("P4B-4 location two groups (comma)", mapOf("spring.config.location" to "optional:classpath:/,optional:file:${ext.absolutePath}/")) { }
    run("P4B-5 location one group (semicolon)", mapOf("spring.config.location" to "optional:classpath:/;optional:file:${ext.absolutePath}/")) { }
}

// ---------- P9 / P9b: 프로파일 ----------

internal fun runP9() {
    val dir = freshDir("p9")
    writeYml(dir, "application-dev.yml", "prop:\n  lab:\n    value: fromDevYml")
    writeYml(dir, "application-local.yml", "prop:\n  lab:\n    value: fromLocalYml")
    val loc = mapOf<String, Any>("spring.config.additional-location" to "file:${dir.absolutePath}/")
    listOf("dev,local", "local,dev").forEach { order ->
        val tag = "P9-files active=$order"
        tryRun(tag, builder(PropLabConfig::class.java).properties(loc), arrayOf("--spring.profiles.active=$order")) { ctx ->
            val env = ctx.environment as ConfigurableEnvironment
            dump("[$tag]", env)
            println("[$tag] activeProfiles=${env.activeProfiles.toList()} getProperty=${env.getProperty(LAB_KEY)} winner=${winner(env, LAB_KEY)}")
        }
    }
    // 한 파일, '---' 멀티 문서. 문서 순서 dev→local 과 local→dev 두 파일로 프로파일 순서와 문서 순서를 가른다.
    fun multiDoc(first: String, second: String): File {
        val d = freshDir("p9-multi-$first-$second")
        File(d, "application.yml").writeText(
            """
            |prop:
            |  lab:
            |    value: fromDefaultDoc
            |---
            |spring:
            |  config:
            |    activate:
            |      on-profile: $first
            |prop:
            |  lab:
            |    value: from${first.replaceFirstChar { it.uppercase() }}Doc
            |---
            |spring:
            |  config:
            |    activate:
            |      on-profile: $second
            |prop:
            |  lab:
            |    value: from${second.replaceFirstChar { it.uppercase() }}Doc
            |""".trimMargin()
        )
        return d
    }
    listOf("dev" to "local", "local" to "dev").forEach { (first, second) ->
        val d = multiDoc(first, second)
        listOf("dev,local", "local,dev").forEach { order ->
            val tag = "P9-multidoc docs=$first→$second active=$order"
            tryRun(tag, builder(PropLabConfig::class.java).properties(mapOf<String, Any>("spring.config.additional-location" to "file:${d.absolutePath}/")),
                arrayOf("--spring.profiles.active=$order")) { ctx ->
                val env = ctx.environment as ConfigurableEnvironment
                dump("[$tag]", env)
                println("[$tag] getProperty=${env.getProperty(LAB_KEY)}")
            }
        }
    }
    println()
    // [P9b-1] 프로파일 전용 파일 안에서 spring.profiles.active 를 다시 켠다
    val b1 = freshDir("p9b-1")
    writeYml(b1, "application-dev.yml", "spring:\n  profiles:\n    active: other\nprop:\n  lab:\n    value: fromDevYml")
    tryRun("P9b-1 spring.profiles.active in application-dev.yml",
        builder(PropLabConfig::class.java).properties(mapOf<String, Any>("spring.config.additional-location" to "file:${b1.absolutePath}/")),
        arrayOf("--spring.profiles.active=dev")) { ctx ->
        println("[P9b-1] started activeProfiles=${ctx.environment.activeProfiles.toList()}")
    }
    println()
    // [P9b-2] 옛 키 spring.profiles (2.3 이전 멀티 문서 문법)
    val b2 = freshDir("p9b-2")
    File(b2, "application.yml").writeText("prop:\n  lab:\n    value: fromDefaultDoc\n---\nspring:\n  profiles: dev\nprop:\n  lab:\n    value: fromDevDoc\n")
    tryRun("P9b-2 legacy spring.profiles key",
        builder(PropLabConfig::class.java).properties(mapOf<String, Any>("spring.config.additional-location" to "file:${b2.absolutePath}/")),
        arrayOf("--spring.profiles.active=dev")) { ctx ->
        println("[P9b-2] started getProperty=${ctx.environment.getProperty(LAB_KEY)}")
    }
}

// ---------- P10 / P10c: location ----------

internal fun runP10() {
    val root = freshDir("p10")
    val a = File(root, "a").apply { mkdirs() }
    val bDir = File(root, "b").apply { mkdirs() }
    writeYml(a, "application.yml", "prop:\n  lab:\n    value: fromA")
    writeYml(bDir, "application.yml", "prop:\n  lab:\n    value: fromB")
    listOf("file:${a.absolutePath}/,file:${bDir.absolutePath}/", "file:${bDir.absolutePath}/,file:${a.absolutePath}/").forEach { locs ->
        val tag = "P10-additional ${if (locs.startsWith("file:${a.absolutePath}")) "a,b" else "b,a"}"
        tryRun(tag, builder(PropLabConfig::class.java).properties(mapOf<String, Any>("spring.config.additional-location" to locs)), emptyArray()) { ctx ->
            val env = ctx.environment as ConfigurableEnvironment
            dump("[$tag]", env)
            println("[$tag] getProperty=${env.getProperty(LAB_KEY)} winner=${winner(env, LAB_KEY)}")
        }
    }
    // spring.config.location 은 기본 위치를 대체한다(classpath application.yaml 이 목록에서 빠지는가)
    tryRun("P10-location defaults", builder(PropLabConfig::class.java).properties(mapOf<String, Any>("spring.config.location" to "file:${a.absolutePath}/")), emptyArray()) { ctx ->
        val env = ctx.environment as ConfigurableEnvironment
        dump("[P10-location defaults]", env)
        println("[P10-location defaults] classpath application.yaml present=${env.propertySources.any { it.name.contains("application.yaml") }} spring.application.name=${env.getProperty("spring.application.name")}")
    }
    tryRun("P10-location cmdline", builder(PropLabConfig::class.java), arrayOf("--spring.config.location=file:${a.absolutePath}/")) { ctx ->
        val env = ctx.environment as ConfigurableEnvironment
        dump("[P10-location cmdline]", env)
        println("[P10-location cmdline] classpath application.yaml present=${env.propertySources.any { it.name.contains("application.yaml") }} spring.application.name=${env.getProperty("spring.application.name")}")
    }
    // 같은 위치에 .properties 와 .yml
    val c = File(root, "c").apply { mkdirs() }
    File(c, "application.properties").writeText("$LAB_KEY=fromProperties\n")
    writeYml(c, "application.yml", "prop:\n  lab:\n    value: fromYml")
    tryRun("P10-same-dir properties+yml", builder(PropLabConfig::class.java).properties(mapOf<String, Any>("spring.config.additional-location" to "file:${c.absolutePath}/")), emptyArray()) { ctx ->
        val env = ctx.environment as ConfigurableEnvironment
        dump("[P10-same-dir]", env)
        println("[P10-same-dir] getProperty=${env.getProperty(LAB_KEY)} winner=${winner(env, LAB_KEY)}")
    }
    println()
    // [P10c] import 파일이 없을 때 optional: 유무
    val missing = File(root, "missing.yml")
    listOf("file:${missing.absolutePath}", "optional:file:${missing.absolutePath}").forEach { imp ->
        val d = freshDir("p10c")
        writeYml(d, "application.yml", "spring:\n  config:\n    import: \"$imp\"\nprop:\n  lab:\n    value: fromApplicationYml")
        val tag = "P10c import=${if (imp.startsWith("optional:")) "optional:file:<missing>" else "file:<missing>"}"
        tryRun(tag, builder(PropLabConfig::class.java).properties(mapOf<String, Any>("spring.config.additional-location" to "file:${d.absolutePath}/")), emptyArray()) { ctx ->
            println("[$tag] started getProperty=${ctx.environment.getProperty(LAB_KEY)}")
        }
        println()
    }
}

// ---------- P11: 리스트 · Map · 네 번째 후보 ----------

class EnvxItem {
    var name: String? = null
    var serverAddress: String? = null
    override fun toString() = "Item(name=$name, serverAddress=$serverAddress)"
}

/** JavaBean 바인딩(인자 없는 primary constructor + var). */
@ConfigurationProperties("prop.envx")
class EnvxProps {
    var items: MutableList<EnvxItem> = mutableListOf()
    var myTargetValue: String? = null
}

/** constructor binding(파라미터가 있는 Kotlin primary constructor). #45741 은 record 에서 났다. */
@ConfigurationProperties("prop.envc")
class EnvcProps(val myTargetValue: String?)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EnvxProps::class, EnvcProps::class, RelaxProps::class)
open class EnvxConfig

private fun printEnvx(tag: String, ctx: ConfigurableApplicationContext) {
    val x = ctx.getBean(EnvxProps::class.java)
    println("[$tag] envx.items=${x.items} envx.myTargetValue=${x.myTargetValue ?: "<null>"}" +
        " envc.myTargetValue=${ctx.getBean(EnvcProps::class.java).myTargetValue ?: "<null>"}" +
        " relax.myTargetValue=${ctx.getBean(RelaxProps::class.java).myTargetValue ?: "<null>"}")
}

internal fun runP11() {
    val vars = listOf(
        "PROP_ENVX_ITEMS_0_NAME",               // 리스트 인덱스 + 한 단어 필드
        "PROP_ENVX_ITEMS_0_SERVERADDRESS",      // 리스트 원소, 공식 표기(대시 제거)
        "PROP_ENVX_ITEMS_0_SERVER_ADDRESS",     // 리스트 원소, 레거시 표기(대시→밑줄). HAPI #178 · #46184 모양
        "PROP_ENVX_MYTARGETVALUE",              // 단일 키 공식 표기
        "PROP_ENVX_MY_TARGET_VALUE",            // 단일 키 레거시(대문자)
        "prop_envx_mytargetvalue",              // 소문자 공식
        "prop_envx_my_target_value",            // 소문자 레거시(네 번째 후보). #45741 모양
        "PROP_ENVX_my_target_value",            // 대문자 접두사 + 소문자 snake
        "prop_envc_my_target_value",            // constructor binding 클래스에 같은 소문자 레거시
        "PROP_ENVC_MY_TARGET_VALUE",            // constructor binding 클래스, 대문자 레거시
        "prop_relax_my_target_value",           // RelaxProps(P6 의 클래스)에 네 번째 후보
    )
    vars.forEach { v ->
        println("[P11] var=$v numberOfElements=${ConfigurationPropertyName.adapt(v, '_').numberOfElements}")
        boot(EnvxConfig::class.java, envVars = mapOf(v to "v-$v")) { ctx -> printEnvx("P11 $v", ctx) }
    }
    // 원소 개수: P6 표기 넷
    listOf("PROP_RELAX_my-target-value", "PROP_RELAX_myTargetValue", "PROP_RELAX_my_target_value", "PROP_RELAX_MY_TARGET_VALUE", "PROP_RELAX_MYTARGETVALUE")
        .forEach { v -> println("[P11] var=$v numberOfElements=${ConfigurationPropertyName.adapt(v, '_').numberOfElements}") }
    println("[P11] canonical prop.relax.my-target-value numberOfElements=${ConfigurationPropertyName.of("prop.relax.my-target-value").numberOfElements}")
    // 리스트 원소 여러 개(인덱스 0..2, 0..4)도 레거시 표기로 붙는가
    listOf(3, 5).forEach { n ->
        val m = (0 until n).associate { "PROP_ENVX_ITEMS_${it}_SERVER_ADDRESS" to "addr-$it" }
        boot(EnvxConfig::class.java, envVars = m) { ctx -> printEnvx("P11 legacy list x$n", ctx) }
        val m2 = (0 until n).associate { "PROP_ENVX_ITEMS_${it}_SERVERADDRESS" to "addr-$it" }
        boot(EnvxConfig::class.java, envVars = m2) { ctx -> printEnvx("P11 official list x$n", ctx) }
    }
    // Map: logging.level.<패키지>
    boot(EnvxConfig::class.java, envVars = mapOf("LOGGING_LEVEL_COM_EXAMPLE" to "DEBUG")) { ctx ->
        val map = Binder.get(ctx.environment).bind("logging.level", Bindable.mapOf(String::class.java, String::class.java)).orElse(emptyMap())
        println("[P11 map] LOGGING_LEVEL_COM_EXAMPLE=DEBUG → logging.level=$map")
    }
}

/** 진짜 OS 환경 변수로 교차 확인. PROP_ENVX_ITEMS_0_SERVER_ADDRESS 를 gradle 명령 앞에 둔다. */
internal fun runP11Os() {
    listOf("PROP_ENVX_ITEMS_0_SERVER_ADDRESS", "PROP_ENVX_ITEMS_0_SERVERADDRESS", "prop_envx_my_target_value").forEach {
        println("[P11OS] OS $it=${System.getenv(it) ?: "<not set>"}")
    }
    boot(EnvxConfig::class.java) { ctx ->
        val env = ctx.environment as ConfigurableEnvironment
        dump("[P11OS]", env)
        printEnvx("P11OS", ctx)
    }
}

// ---------- P14 / P14b: @Value 와 PSPC ----------

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelaxProps::class)
open class SplitPspcConfig {
    companion object {
        @JvmStatic
        @Bean
        fun pspc() = PropertySourcesPlaceholderConfigurer()
    }

    @Bean
    open fun valueHolder(
        @Value("\${prop.relax.my-target-value:<unresolved>}") dashed: String,
        @Value("\${prop.relax.myTargetValue:<unresolved>}") camel: String,
    ) = ValueHolder(dashed, camel)
}

class StrictHolder(val camel: String)

/** 기본값 없는 @Value. PSPC 없음. */
@Configuration(proxyBeanMethods = false)
open class StrictNoPspcConfig {
    @Bean
    open fun strictHolder(@Value("\${prop.relax.myTargetValue}") camel: String) = StrictHolder(camel)
}

/** 기본값 없는 @Value. PSPC 있음(Boot auto-config 가 넣는 것과 같은 Bean). */
@Configuration(proxyBeanMethods = false)
open class StrictPspcConfig {
    companion object {
        @JvmStatic
        @Bean
        fun pspc() = PropertySourcesPlaceholderConfigurer()
    }

    @Bean
    open fun strictHolder(@Value("\${prop.relax.myTargetValue}") camel: String) = StrictHolder(camel)
}

/** 파일 키 없이 진짜 OS env PROP_RELAX_MYTARGETVALUE=fromEnv 만 둔다(gradle 명령 앞). */
internal fun runP14() {
    println("[P14] OS PROP_RELAX_MYTARGETVALUE=${System.getenv("PROP_RELAX_MYTARGETVALUE") ?: "<not set>"}")
    listOf("no-PSPC" to SplitConfig::class.java, "PSPC" to SplitPspcConfig::class.java).forEach { (cond, cfg) ->
        boot(cfg) { ctx ->
            val env = ctx.environment as ConfigurableEnvironment
            val h = ctx.getBean(ValueHolder::class.java)
            println("[P14] condition=$cond @Value(my-target-value)=${h.dashed} @Value(myTargetValue)=${h.camel} @ConfigurationProperties=${ctx.getBean(RelaxProps::class.java).myTargetValue ?: "<null>"}")
            println("[P14] condition=$cond env.getProperty(prop.relax.my-target-value)=${env.getProperty("prop.relax.my-target-value")} env.getProperty(prop.relax.myTargetValue)=${env.getProperty("prop.relax.myTargetValue")}")
            println("[P14] condition=$cond systemEnvironment.containsProperty(prop.relax.myTargetValue)=${env.propertySources.get("systemEnvironment")?.containsProperty("prop.relax.myTargetValue")}" +
                " configurationProperties.containsProperty(prop.relax.myTargetValue)=${env.propertySources.get("configurationProperties")?.containsProperty("prop.relax.myTargetValue")}" +
                " configurationProperties.containsProperty(prop.relax.my-target-value)=${env.propertySources.get("configurationProperties")?.containsProperty("prop.relax.my-target-value")}")
        }
    }
}

/** 기본값 없는 @Value("${prop.relax.myTargetValue}") + kebab 파일 키. */
internal fun runP14b() {
    val dir = freshDir("p14b")
    writeYml(dir, "application.yml", "prop:\n  relax:\n    my-target-value: fromKebabFileKey")
    val loc = mapOf<String, Any>("spring.config.additional-location" to "file:${dir.absolutePath}/")
    listOf("no-PSPC" to StrictNoPspcConfig::class.java, "PSPC" to StrictPspcConfig::class.java).forEach { (cond, cfg) ->
        val tag = "P14b condition=$cond"
        tryRun(tag, builder(cfg).properties(loc), emptyArray()) { ctx ->
            println("[$tag] started injected=${ctx.getBean(StrictHolder::class.java).camel}")
        }
        println()
    }
}

// ---------- P17: BindMethod ----------

/** 인자 없는 primary constructor + var 필드(비교용 JavaBean). */
@ConfigurationProperties("prop.jb")
class JavaBeanProps {
    var v: String? = null
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelaxProps::class, JavaBeanProps::class, EnvcProps::class)
open class BindMethodConfig

internal fun runP17() {
    val dir = freshDir("p17")
    writeYml(dir, "application.yml", "prop:\n  relax:\n    my-target-value: before\n  jb:\n    v: before\n  envc:\n    my-target-value: before")
    boot(BindMethodConfig::class.java, props = mapOf("spring.config.additional-location" to "file:${dir.absolutePath}/")) { ctx ->
        val bf = ctx.beanFactory
        listOf(RelaxProps::class.java, JavaBeanProps::class.java, EnvcProps::class.java).forEach { type ->
            ctx.getBeanNamesForType(type).forEach { name ->
                val bean = ctx.getBean(name)
                val cpb = ConfigurationPropertiesBean.get(ctx, bean, name)!!
                val bd = bf.getBeanDefinition(name)
                println("[P17] type=${type.simpleName} beanName=$name bindMethod=${cpb.asBindTarget().bindMethod}" +
                    " beanDefinition=${bd.javaClass.simpleName} attr(BindMethod)=${bd.getAttribute("org.springframework.boot.context.properties.bind.BindMethod")}" +
                    " ctorParams=${type.declaredConstructors.map { it.parameterCount }}")
            }
        }
        println("[P17] RelaxProps.myTargetValue=${ctx.getBean(RelaxProps::class.java).myTargetValue} JavaBeanProps.v=${ctx.getBean(JavaBeanProps::class.java).v} EnvcProps.myTargetValue=${ctx.getBean(EnvcProps::class.java).myTargetValue}")
    }
}

// ---------- P18: Kubernetes Service 변수와 prefix ----------

@ConfigurationProperties("redis.primary")
class RedisPrimaryProps {
    var port: Int? = null
    var host: String? = null
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisPrimaryProps::class)
open class RedisPrimaryConfig

/** 진짜 OS env 로 Kubernetes 문서 예시 변수를 gradle 명령 앞에 둔다(REDIS_PRIMARY_PORT 하나 / 문서의 7개 전부). */
internal fun runP18() {
    val k8s = System.getenv().filterKeys { it.startsWith("REDIS_PRIMARY") }.toSortedMap()
    if (k8s.isEmpty()) println("[P18] OS REDIS_PRIMARY*=<not set>")
    k8s.forEach { (k, v) -> println("[P18] OS $k=$v") }
    tryRun("P18", builder(RedisPrimaryConfig::class.java), emptyArray()) { ctx ->
        val p = ctx.getBean(RedisPrimaryProps::class.java)
        println("[P18] started redis.primary.port=${p.port} redis.primary.host=${p.host}")
    }
}
