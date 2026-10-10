package com.petercoders.blog.prop

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.io.File
import kotlin.system.exitProcess

// SPRING-INTERNALS 4편 실험 러너. 마커 목록과 태스크는 README.md 의 실험 표를 본다.
//   ./gradlew --offline -q propLab --args='P1'
// 공용 src/main/resources/application.yaml 은 건드리지 않는다.
// 실험용 yml 은 전부 java.io.tmpdir/proplab-<nanos>/ 에 프로브가 직접 쓰고
// spring.config.additional-location 으로 읽어 들인다.

@Configuration(proxyBeanMethods = false)
open class PropLabConfig

@ConfigurationProperties(prefix = "prop.relax")
data class RelaxProps(var myTargetValue: String? = null)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelaxProps::class)
open class RelaxConfig

class ValueHolder(val dashed: String, val camel: String)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelaxProps::class)
open class SplitConfig {
    @org.springframework.context.annotation.Bean
    open fun valueHolder(
        @Value("\${prop.relax.my-target-value:<unresolved>}") dashed: String,
        @Value("\${prop.relax.myTargetValue:<unresolved>}") camel: String,
    ) = ValueHolder(dashed, camel)
}

// ---------- 공용 도구 ----------
// 아래 도구는 PropLabPlus.kt·PropLabExtra.kt 도 쓴다(같은 패키지, internal).

/** 로그 머리에 찍는 실행 환경 한 줄(E1). 로그에 없는 버전은 본문에 쓰지 않는다. */
internal fun envLine(): String =
    "env: java.runtime.version=${System.getProperty("java.runtime.version")}" +
        " java.vendor=${System.getProperty("java.vendor")}" +
        " java.vm.name=${System.getProperty("java.vm.name")}" +
        " java.home=${System.getProperty("java.home")}" +
        " / kotlin=${KotlinVersion.CURRENT}" +
        " spring-boot=${org.springframework.boot.SpringBootVersion.getVersion()}" +
        " spring-framework=${org.springframework.core.SpringVersion.getVersion()}" +
        " spring-security=${runCatching { org.springframework.security.core.SpringSecurityCoreVersion.getVersion() }.getOrDefault("<absent>")}" +
        " / user.dir=${System.getProperty("user.dir")}"

internal fun freshDir(tag: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "proplab-$tag-${System.nanoTime()}")
    dir.mkdirs()
    return dir
}

/** PropertySource 목록 전체. 이름은 자르지 않는다(옛 로그는 52자에서 잘려 있었다). */
internal fun dump(marker: String, env: ConfigurableEnvironment) {
    env.propertySources.forEachIndexed { i, ps ->
        val tag = if (ps.name == "configurationProperties") " (창구)" else ""
        println("$marker[%02d] %s%s :: %s".format(i, ps.name, tag, ps.javaClass.simpleName))
    }
}

/**
 * key 를 실제로 들고 있는 첫 소스 이름. 조회가 멈추는 자리다.
 * 맨 앞 configurationProperties 는 뒤 소스를 감싼 창구라 항상 먼저 걸린다. 그래서 건너뛰고 실제 소스를 찍는다.
 */
internal fun winner(env: ConfigurableEnvironment, key: String): String =
    env.propertySources.firstOrNull { it.name != "configurationProperties" && it.containsProperty(key) }?.name ?: "<none>"

/** key 를 가진 소스를 목록 순서대로 전부. configurationProperties 줄에는 (창구)를 붙인다. */
internal fun holds(marker: String, env: ConfigurableEnvironment, key: String) {
    env.propertySources.forEachIndexed { i, ps ->
        if (ps.containsProperty(key)) {
            val tag = if (ps.name == "configurationProperties") " (창구)" else ""
            println("$marker holds[%02d] value=%s :: %s%s".format(i, ps.getProperty(key), ps.name, tag))
        }
    }
}

/** 기동 실패를 잡아 예외 클래스와 메시지 첫 줄, cause 사슬을 찍는다. */
internal fun failure(marker: String, t: Throwable) {
    var c: Throwable? = t
    var depth = 0
    while (c != null && depth < 8) {
        println("$marker failure[$depth] ${c.javaClass.name}: ${c.message?.lineSequence()?.firstOrNull()}")
        c = if (c.cause === c) null else c.cause
        depth++
    }
}

internal fun boot(
    source: Class<*>,
    args: Array<String> = emptyArray(),
    props: Map<String, Any> = emptyMap(),
    profiles: Array<String> = emptyArray(),
    defaults: Map<String, Any> = emptyMap(),
    envVars: Map<String, Any> = emptyMap(),
    block: (ConfigurableApplicationContext) -> Unit,
) {
    val builder = SpringApplicationBuilder(source)
        .web(WebApplicationType.NONE)
        .bannerMode(org.springframework.boot.Banner.Mode.OFF)
        .properties(props)
    if (defaults.isNotEmpty()) builder.properties(*defaults.map { "${it.key}=${it.value}" }.toTypedArray())
    if (profiles.isNotEmpty()) builder.profiles(*profiles)
    if (envVars.isNotEmpty()) {
        builder.initializers({ ctx: ConfigurableApplicationContext ->
            // 이름이 "-systemEnvironment" 로 끝나야 SpringConfigurationPropertySource 가
            // SYSTEM_ENVIRONMENT_MAPPERS 를 붙인다(isSystemEnvironmentPropertySource).
            ctx.environment.propertySources.addFirst(
                SystemEnvironmentPropertySource("proplab-systemEnvironment", envVars.toMutableMap())
            )
        })
    }
    builder.run(*args).use(block)
}

internal fun writeYml(dir: File, name: String, body: String): File =
    File(dir, name).apply { writeText(body.trimIndent() + "\n") }

// ---------- P1: 이 앱의 PropertySource 목록 실물 ----------

private fun runP1() {
    boot(PropLabConfig::class.java) { ctx ->
        dump("[P1]", ctx.environment as ConfigurableEnvironment)
        println("[P1] total=${(ctx.environment as ConfigurableEnvironment).propertySources.count()}")
        println("[P1] servletContextInitParams present=" +
            (ctx.environment as ConfigurableEnvironment).propertySources.any { it.name.contains("servlet", true) })
    }
}

// ---------- P2: 프로파일 비활성 vs dev 활성 ----------

private fun runP2() {
    val dir = freshDir("p2")
    writeYml(dir, "application.yml", "prop:\n  lab:\n    value: fromApplicationYml")
    writeYml(dir, "application-dev.yml", "prop:\n  lab:\n    value: fromProfileYml")
    val loc = mapOf("spring.config.additional-location" to "file:${dir.absolutePath}/")
    boot(PropLabConfig::class.java, props = loc) { ctx ->
        dump("[P2-off]", ctx.environment as ConfigurableEnvironment)
        println("[P2-off] value=${ctx.environment.getProperty("prop.lab.value")} from=${winner(ctx.environment as ConfigurableEnvironment, "prop.lab.value")}")
        holds("[P2-off]", ctx.environment as ConfigurableEnvironment, "prop.lab.value")
    }
    boot(PropLabConfig::class.java, props = loc, profiles = arrayOf("dev")) { ctx ->
        dump("[P2-dev]", ctx.environment as ConfigurableEnvironment)
        println("[P2-dev] value=${ctx.environment.getProperty("prop.lab.value")} from=${winner(ctx.environment as ConfigurableEnvironment, "prop.lab.value")}")
        holds("[P2-dev]", ctx.environment as ConfigurableEnvironment, "prop.lab.value")
    }
}

// ---------- P3: spring.config.import 문서의 삽입 위치 ----------

private fun runP3() {
    val dir = freshDir("p3")
    val ext = writeYml(dir, "external.yml", "prop:\n  lab:\n    value: fromImportFile")
    writeYml(
        dir, "application.yml",
        """
        spring:
          config:
            import: "optional:file:${ext.absolutePath}"
        prop:
          lab:
            value: fromApplicationYml
        """
    )
    val loc = mapOf("spring.config.additional-location" to "file:${dir.absolutePath}/")
    boot(PropLabConfig::class.java, props = loc) { ctx ->
        dump("[P3]", ctx.environment as ConfigurableEnvironment)
        println("[P3] value=${ctx.environment.getProperty("prop.lab.value")} from=${winner(ctx.environment as ConfigurableEnvironment, "prop.lab.value")}")
        holds("[P3]", ctx.environment as ConfigurableEnvironment, "prop.lab.value")
    }
}

// ---------- P4: 같은 키를 일곱 곳에 ----------

private fun runP4() {
    val dir = freshDir("p4")
    val ext = writeYml(dir, "external.yml", "prop:\n  lab:\n    value: fromImportFile")
    writeYml(
        dir, "application.yml",
        """
        spring:
          config:
            import: "optional:file:${ext.absolutePath}"
        prop:
          lab:
            value: fromApplicationYml
        """
    )
    writeYml(dir, "application-dev.yml", "prop:\n  lab:\n    value: fromProfileYml")
    System.setProperty("prop.lab.value", "fromSystemProperty")
    val osEnv = System.getenv("PROP_LAB_VALUE")
    println("[P4] OS PROP_LAB_VALUE=${osEnv ?: "<not set>"}")
    boot(
        PropLabConfig::class.java,
        args = arrayOf("--prop.lab.value=fromCommandLine"),
        props = mapOf("spring.config.additional-location" to "file:${dir.absolutePath}/"),
        profiles = arrayOf("dev"),
        defaults = emptyMap(),
        envVars = mapOf("PROP_LAB_VALUE" to "fromEnvVar"),
    ) { ctx ->
        val env = ctx.environment as ConfigurableEnvironment
        env.propertySources.addLast(MapPropertySource("defaultProperties", mapOf("prop.lab.value" to "fromDefaults")))
        dump("[P4]", env)
        println("[P4] winner-value=${env.getProperty("prop.lab.value")}")
        println("[P4] winner-source=${winner(env, "prop.lab.value")}")
        env.propertySources.forEach { ps ->
            if (ps.containsProperty("prop.lab.value")) {
                println("[P4] holds value=${ps.getProperty("prop.lab.value")} :: ${ps.name}")
            }
        }
    }
    System.clearProperty("prop.lab.value")
}

// ---------- P5: 파일 프로퍼티 4표기 ----------

private val SPELLINGS = listOf(
    "kebab" to "prop.relax.my-target-value",
    "camel" to "prop.relax.myTargetValue",
    "snake" to "prop.relax.my_target_value",
    "upper" to "prop.relax.MY_TARGET_VALUE",
)

private fun runP5() {
    SPELLINGS.forEach { (tag, key) ->
        val dir = freshDir("p5-$tag")
        writeYml(dir, "application.yml", "\"$key\": from-$tag")
        boot(
            RelaxConfig::class.java,
            props = mapOf("spring.config.additional-location" to "file:${dir.absolutePath}/"),
        ) { ctx ->
            val bound = ctx.getBean(RelaxProps::class.java).myTargetValue
            println("[P5] file spelling=$tag key=$key bound=${bound ?: "<null>"}")
        }
    }
}

// ---------- P6: 환경변수 4표기 ----------

private fun runP6() {
    SPELLINGS.forEach { (tag, key) ->
        val envName = key.removePrefix("prop.relax.")
        val varName = "PROP_RELAX_" + envName.replace('.', '_')
        boot(RelaxConfig::class.java, envVars = mapOf(varName to "from-$tag")) { ctx ->
            val bound = ctx.getBean(RelaxProps::class.java).myTargetValue
            println("[P6] env spelling=$tag var=$varName bound=${bound ?: "<null>"}")
        }
    }
    // 레거시 형태(대시를 밑줄로 바꾼 이름)까지 포함해 후보 문자열 네 개를 확인한다.
    listOf("PROP_RELAX_MYTARGETVALUE", "PROP_RELAX_MY_TARGET_VALUE", "prop_relax_mytargetvalue")
        .forEach { varName ->
            boot(RelaxConfig::class.java, envVars = mapOf(varName to "from-$varName")) { ctx ->
                println("[P6] candidate var=$varName bound=${ctx.getBean(RelaxProps::class.java).myTargetValue ?: "<null>"}")
            }
        }
}

// ---------- P7: @Value 와 @ConfigurationProperties 의 갈림 ----------

private fun runP7() {
    // [P14 재설계] PropLabConfig/SplitConfig 에는 auto-config 가 없어 PropertySourcesPlaceholderConfigurer(PSPC) Bean 이 없다.
    // 그래서 PSPC 없음(SplitConfig)과 있음(SplitPspcConfig, PropLabPlus.kt) 두 조건으로 같은 파일을 읽는다.
    listOf("no-PSPC" to SplitConfig::class.java, "PSPC" to SplitPspcConfig::class.java).forEach { (cond, cfg) ->
        listOf("prop.relax.my-target-value", "prop.relax.myTargetValue").forEach { key ->
            val dir = freshDir("p7")
            writeYml(dir, "application.yml", "\"$key\": written-as-$key")
            boot(
                cfg,
                props = mapOf("spring.config.additional-location" to "file:${dir.absolutePath}/"),
            ) { ctx ->
                val holder = ctx.getBean(ValueHolder::class.java)
                val props = ctx.getBean(RelaxProps::class.java)
                println("[P7] condition=$cond config=${cfg.simpleName} pspcBeans=${ctx.getBeanNamesForType(org.springframework.context.support.PropertySourcesPlaceholderConfigurer::class.java).toList()}")
                println("[P7] file-key=$key")
                println("[P7]   @Value(my-target-value)=${holder.dashed}")
                println("[P7]   @Value(myTargetValue)  =${holder.camel}")
                println("[P7]   @ConfigurationProperties=${props.myTargetValue ?: "<null>"}")
            }
        }
    }
}

// ---------- P8: @ConfigurationProperties 는 시작 시점 스냅샷인가 ----------

private fun runP8() {
    val dir = freshDir("p8")
    writeYml(dir, "application.yml", "prop:\n  relax:\n    my-target-value: before")
    boot(
        RelaxConfig::class.java,
        props = mapOf("spring.config.additional-location" to "file:${dir.absolutePath}/"),
    ) { ctx ->
        val env = ctx.environment as ConfigurableEnvironment
        println("[P8] bound-before=${ctx.getBean(RelaxProps::class.java).myTargetValue}")
        env.propertySources.addFirst(
            MapPropertySource("p8-override", mapOf("prop.relax.my-target-value" to "after"))
        )
        println("[P8] env-after-change=${env.getProperty("prop.relax.my-target-value")}")
        println("[P8] bean-reget=${ctx.getBean(RelaxProps::class.java).myTargetValue}")
        println("[P8] same-instance=${ctx.getBean(RelaxProps::class.java) === ctx.getBean(RelaxProps::class.java)}")
    }
}

fun main(args: Array<String>) {
    println(envLine())
    val only = args.firstOrNull()?.uppercase()
    val rest = args.drop(1).toTypedArray()
    fun step(tag: String, f: () -> Unit) { if (only == null || only == tag) { f(); println() } }
    step("P1") { runP1() }
    step("P2") { runP2() }
    step("P3") { runP3() }
    if (only == "P4LEGACY") { runP4(); exitProcess(0) }      // 옛 P4(인공 소스 둘 섞임). runs/P4-legacy.log 로 보존만 한다
    step("P4") { runP4Clean(rest) }                       // P4' (PropLabPlus.kt)
    step("P4B") { runP4b() }                              // propLabCwd 태스크로만 돈다(workingDir)
    step("P5") { runP5() }
    step("P6") { runP6() }
    step("P7") { runP7() }
    step("P8") { runP8() }
    step("P9") { runP9() }
    step("P10") { runP10() }
    step("P11") { runP11() }
    step("P11OS") { runP11Os() }
    step("P13") { runP13() }                              // propLabActuator 태스크(main + propLabExtra)로만 돈다
    step("P14") { runP14() }
    step("P14B") { runP14b() }
    step("P15") { runP15() }                              // propLabExtra 태스크(main + propLabExtra)로만 돈다
    step("P17") { runP17() }
    step("P18") { runP18() }
    exitProcess(0)
}
