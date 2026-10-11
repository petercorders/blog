package com.petercoders.blog.prop

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEvent
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DeferredImportSelector
import org.springframework.context.annotation.Import
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.type.AnnotationMetadata
import org.springframework.validation.annotation.Validated
import java.io.File

// SPRING-INTERNALS 4편 재실측 중 별도 클래스패스가 필요한 마커.
//   P13 : actuator /env · /configprops 를 실제 HTTP(curl)로 (propLabActuator 태스크: main + propLabExtra)
//   P15 : @Validated · ignoreUnknownFields · properties-migrator (propLabExtra 태스크: main + propLabExtra)
//   P15D: Kotlin 생성자 프로퍼티의 @NotBlank 가 붙는 자리(no-target / @param: / @field:)와 검증 여부 (propLabExtra 태스크)
//   P19 : Spring Cloud ConfigurationPropertiesRebinder 의 EnvironmentChangeEvent 재바인딩 (propLabCloud 태스크: main + propLabCloud)
// actuator·validation·migrator 는 propLabExtra configuration 에만 있다. main runtimeClasspath 에는 없다.

// ---------- P13: actuator ----------

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableConfigurationProperties(RelaxProps::class)
open class ActuatorLabApp

private fun curl(tag: String, vararg args: String): String {
    val cmd = listOf("curl", "-s", "-S") + args
    println("[$tag] $ ${cmd.joinToString(" ")}")
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return out
}

internal fun runP13() {
    val dir = freshDir("p13")
    writeYml(dir, "application.yml", "prop:\n  relax:\n    my-target-value: before")
    val base = mapOf<String, Any>(
        "server.port" to "0",
        "spring.config.additional-location" to "file:${dir.absolutePath}/",
        "management.endpoints.web.exposure.include" to "env,configprops",
        "spring.security.user.name" to "lab",
        "spring.security.user.password" to "lab",
    )
    val scenarios = listOf(
        "P13-never(default)" to emptyMap(),
        "P13-when-authorized" to mapOf<String, Any>(
            "management.endpoint.env.show-values" to "when-authorized",
            "management.endpoint.configprops.show-values" to "when-authorized",
        ),
        "P13-always" to mapOf<String, Any>(
            "management.endpoint.env.show-values" to "always",
            "management.endpoint.configprops.show-values" to "always",
        ),
    )
    scenarios.forEach { (tag, extra) ->
        SpringApplicationBuilder(ActuatorLabApp::class.java)
            .web(WebApplicationType.SERVLET)
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .properties(base + extra)
            .run().use { ctx: ConfigurableApplicationContext ->
                val env = ctx.environment as ConfigurableEnvironment
                val port = env.getProperty("local.server.port")
                val root = "http://localhost:$port/actuator"
                println("[$tag] local.server.port=$port")
                println("[$tag] " + curl(tag, "-o", "/dev/null", "-w", "unauthenticated http=%{http_code}", "$root/env/prop.relax.my-target-value"))
                println("[$tag] env/{key} body=" + curl(tag, "-u", "lab:lab", "$root/env/prop.relax.my-target-value"))
                println("[$tag] configprops/{prefix} body=" + curl(tag, "-u", "lab:lab", "$root/configprops/prop.relax"))
                // 전체 /env 본문은 이 기기의 OS 환경 변수 이름을 전부 담고 있어 원문을 남기지 않는다. 소스 이름만 뽑는다.
                val all = curl(tag, "-u", "lab:lab", "$root/env")
                val names = Regex("\\{\"name\":\"((?:[^\"\\\\]|\\\\.)*)\",\"properties\"").findAll(all).map { it.groupValues[1] }.toList()
                println("[$tag] env (full body not logged) propertySources.name in order:")
                names.forEachIndexed { i, n -> println("[$tag]   [%02d] %s".format(i, n)) }
                println("[$tag] env contains configurationProperties source=${names.contains("configurationProperties")}")
                if (tag == "P13-always") {
                    env.propertySources.addFirst(MapPropertySource("p13-override", mapOf("prop.relax.my-target-value" to "after")))
                    println("[$tag] after addFirst(p13-override: prop.relax.my-target-value=after)")
                    println("[$tag] env/{key} body=" + curl(tag, "-u", "lab:lab", "$root/env/prop.relax.my-target-value"))
                    println("[$tag] configprops/{prefix} body=" + curl(tag, "-u", "lab:lab", "$root/configprops/prop.relax"))
                }
            }
        println()
    }
}

// ---------- P15: 잘못된 키를 기동에서 거르기 ----------

@ConfigurationProperties("prop.val")
@Validated
class ValProps {
    @field:NotBlank
    var name: String? = null
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ValProps::class)
open class ValConfig

@ConfigurationProperties(prefix = "prop.strict", ignoreUnknownFields = false)
class StrictProps {
    var myTargetValue: String? = null
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StrictProps::class)
open class StrictConfig

private fun nb(source: Class<*>) = SpringApplicationBuilder(source)
    .web(WebApplicationType.NONE)
    .bannerMode(org.springframework.boot.Banner.Mode.OFF)

private fun tryBoot(tag: String, b: SpringApplicationBuilder, block: (ConfigurableApplicationContext) -> Unit) {
    try {
        b.run().use(block)
    } catch (t: Throwable) {
        failure("[$tag]", t)
    }
}

/** classpath 의 모든 metadata json 에서 name 이 정확히 key 인 항목 객체를 통째로 뽑는다. */
private fun metadataEntries(key: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    listOf("META-INF/spring-configuration-metadata.json", "META-INF/additional-spring-configuration-metadata.json").forEach { res ->
        Thread.currentThread().contextClassLoader.getResources(res).toList().forEach { url ->
            val text = url.openStream().bufferedReader().readText()
            var idx = text.indexOf("\"name\": \"$key\"")
            while (idx >= 0) {
                val start = text.lastIndexOf('{', idx)
                var depth = 0
                var end = start
                for (i in start until text.length) {
                    if (text[i] == '{') depth++
                    if (text[i] == '}') { depth--; if (depth == 0) { end = i; break } }
                }
                out += url.toString().substringAfterLast("/files-2.1/").ifEmpty { url.toString() } to
                    text.substring(start, end + 1).replace(Regex("\\s+"), " ")
                idx = text.indexOf("\"name\": \"$key\"", end)
            }
        }
    }
    return out
}

internal fun runP15() {
    println("[P15] migrator on classpath=${runCatching { Class.forName("org.springframework.boot.context.properties.migrator.PropertiesMigrationListener"); true }.getOrDefault(false)}" +
        " validator on classpath=${runCatching { Class.forName("org.hibernate.validator.HibernateValidator"); true }.getOrDefault(false)}")
    // [P15a] @Validated + @NotBlank, 값 없음
    tryBoot("P15a no value", nb(ValConfig::class.java)) { ctx -> println("[P15a] started name=${ctx.getBean(ValProps::class.java).name}") }
    tryBoot("P15a with value", nb(ValConfig::class.java).properties("prop.val.name=ok")) { ctx -> println("[P15a] started name=${ctx.getBean(ValProps::class.java).name}") }
    println()

    // [P15b] 오타 키
    println("[P15b] @ConfigurationProperties.ignoreUnknownFields default=${ConfigurationProperties::class.java.getMethod("ignoreUnknownFields").defaultValue}")
    val typo = freshDir("p15b")
    writeYml(typo, "application.yml", "prop:\n  relax:\n    my-targt-value: typo\n  strict:\n    my-targt-value: typo")
    val loc = mapOf<String, Any>("spring.config.additional-location" to "file:${typo.absolutePath}/")
    tryBoot("P15b default (RelaxProps)", nb(RelaxConfig::class.java).properties(loc)) { ctx ->
        println("[P15b default] started RelaxProps.myTargetValue=${ctx.getBean(RelaxProps::class.java).myTargetValue ?: "<null>"}")
    }
    tryBoot("P15b ignoreUnknownFields=false (StrictProps)", nb(StrictConfig::class.java).properties(loc)) { ctx ->
        println("[P15b strict] started StrictProps.myTargetValue=${ctx.getBean(StrictProps::class.java).myTargetValue ?: "<null>"}")
    }
    println()

    // [P15c] metadata 와 migrator
    listOf(
        "server.error.include-message", "spring.web.error.include-message",
        "management.endpoints.enabled-by-default", "management.endpoints.access.default",
        "spring.redis.host", "spring.data.redis.host",
        "spring.resources.add-mappings", "spring.web.resources.add-mappings",   // 대조군: 타입이 같은 deprecated 키
    ).forEach { key ->
        val entries = metadataEntries(key)
        if (entries.isEmpty()) println("[P15c] metadata $key: <none>")
        entries.forEach { (where, json) -> println("[P15c] metadata $key @ $where :: $json") }
    }
    val mig = freshDir("p15c")
    writeYml(mig, "application.yml", "server:\n  error:\n    include-message: always\nspring:\n  redis:\n    host: redis.example\n  resources:\n    add-mappings: false")
    tryBoot("P15c yml", nb(PropLabConfig::class.java).properties(mapOf<String, Any>("spring.config.additional-location" to "file:${mig.absolutePath}/"))) { ctx ->
        val env = ctx.environment as ConfigurableEnvironment
        dump("[P15c yml]", env)
        listOf("server.error.include-message", "spring.web.error.include-message", "spring.redis.host", "spring.data.redis.host", "spring.resources.add-mappings", "spring.web.resources.add-mappings").forEach { k ->
            println("[P15c yml] $k=${env.getProperty(k)} winner=${winner(env, k)}")
        }
    }
    println()
    val ps = freshDir("p15c-ps")
    val psFile = File(ps, "ps.properties").apply { writeText("server.error.include-message=always\nspring.redis.host=redis.example\nspring.resources.add-mappings=false\n") }
    PropMarker.current = "P15c @PropertySource"
    PropMarker.key = "server.error.include-message"
    tryBoot("P15c @PropertySource", nb(PsConfig::class.java).properties(mapOf<String, Any>("proplab.ps.file" to psFile.absolutePath))) { ctx ->
        val env = ctx.environment as ConfigurableEnvironment
        dump("[P15c @PropertySource]", env)
        listOf("server.error.include-message", "spring.web.error.include-message", "spring.redis.host", "spring.data.redis.host", "spring.resources.add-mappings", "spring.web.resources.add-mappings").forEach { k ->
            println("[P15c @PropertySource] $k=${env.getProperty(k)} winner=${winner(env, k)}")
        }
    }
    PropMarker.key = "prop.lab.value"
}

// ---------- P15d: Kotlin 생성자 프로퍼티에 단 검증 annotation 은 어디에 붙고, 검증이 도는가 ----------
// 이 저장소는 -Xannotation-default-target=param-property 로 컴파일한다(build.gradle.kts). use-site target 이 없으면
// 생성자 파라미터와 (적용 가능하면) 필드에 같이 붙는 규칙이다. @param: 은 생성자 파라미터에만 붙인다.
// Boot 는 바인딩이 끝난 객체를 Bean Validation 으로 검사하므로 필드·getter 의 제약만 본다.

@Validated
@ConfigurationProperties("prop.vcn")
class ValCtorNoTarget(@NotBlank val name: String?)

@Validated
@ConfigurationProperties("prop.vcp")
class ValCtorParam(@param:NotBlank val name: String?)

@Validated
@ConfigurationProperties("prop.vcf")
class ValCtorField(@field:NotBlank val name: String?)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ValCtorNoTarget::class)
open class ValCtorNoTargetConfig

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ValCtorParam::class)
open class ValCtorParamConfig

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ValCtorField::class)
open class ValCtorFieldConfig

internal fun runP15d() {
    listOf(
        Triple("no-target", ValCtorNoTargetConfig::class.java, ValCtorNoTarget::class.java),
        Triple("@param:", ValCtorParamConfig::class.java, ValCtorParam::class.java),
        Triple("@field:", ValCtorFieldConfig::class.java, ValCtorField::class.java),
    ).forEach { (tag, cfg, type) ->
        val onField = type.getDeclaredField("name").isAnnotationPresent(NotBlank::class.java)
        val onParam = type.declaredConstructors.single { it.parameterCount == 1 }.parameters[0].isAnnotationPresent(NotBlank::class.java)
        val onGetter = type.getMethod("getName").isAnnotationPresent(NotBlank::class.java)
        println("[P15d $tag] ${type.simpleName} @NotBlank on field=$onField ctorParam=$onParam getter=$onGetter")
        tryBoot("P15d $tag no value", nb(cfg)) { ctx ->
            println("[P15d $tag] started name=${type.getMethod("getName").invoke(ctx.getBean(type)) ?: "<null>"}")
        }
        println()
    }
}

// ---------- P19: Spring Cloud EnvironmentChangeEvent 재바인딩 ----------
// spring-cloud-context 5.0.3 은 propLabCloud configuration 에만 있다(propLabCloud 태스크). main 컴파일 클래스패스에
// 넣지 않으려고 Spring Cloud 클래스는 이름 문자열과 리플렉션으로만 쓴다.
// ConfigurationPropertiesRebinderAutoConfiguration 은 @ConditionalOnBean(ConfigurationPropertiesBindingPostProcessor) 라
// 사용자 설정이 다 읽힌 뒤 평가되도록 DeferredImportSelector 로 들인다.

class CloudRebinderImport : DeferredImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        arrayOf("org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration")
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelaxProps::class, JavaBeanProps::class, EnvcProps::class)
@Import(CloudRebinderImport::class)
open class CloudRebindConfig

internal fun runP19() {
    val evtClass = Class.forName("org.springframework.cloud.context.environment.EnvironmentChangeEvent")
    val rebinderClass = Class.forName("org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder")
    println("[P19] spring-cloud-context jar=${evtClass.protectionDomain.codeSource.location.path.substringAfterLast('/')}")
    val dir = freshDir("p19")
    writeYml(dir, "application.yml", "prop:\n  relax:\n    my-target-value: before\n  jb:\n    v: before\n  envc:\n    my-target-value: before")
    tryBoot("P19", nb(CloudRebindConfig::class.java).properties(mapOf<String, Any>("spring.config.additional-location" to "file:${dir.absolutePath}/"))) { ctx ->
        val rebinder = ctx.getBean(rebinderClass)
        println("[P19] rebinder beanNames=${rebinderClass.getMethod("getBeanNames").invoke(rebinder)}")
        listOf(RelaxProps::class.java, JavaBeanProps::class.java, EnvcProps::class.java).forEach { t ->
            val name = ctx.getBeanNamesForType(t).single()
            val cpb = org.springframework.boot.context.properties.ConfigurationPropertiesBean.get(ctx, ctx.getBean(name), name)!!
            println("[P19] ${t.simpleName} bindMethod=${cpb.asBindTarget().bindMethod} ctorParams=${t.declaredConstructors.map { it.parameterCount }}")
        }
        val r0 = ctx.getBean(RelaxProps::class.java)
        val j0 = ctx.getBean(JavaBeanProps::class.java)
        val e0 = ctx.getBean(EnvcProps::class.java)
        fun show(stage: String) {
            val r = ctx.getBean(RelaxProps::class.java); val j = ctx.getBean(JavaBeanProps::class.java); val e = ctx.getBean(EnvcProps::class.java)
            println("[P19:$stage] RelaxProps.myTargetValue=${r.myTargetValue ?: "<null>"}(same=${r === r0})" +
                " JavaBeanProps.v=${j.v ?: "<null>"}(same=${j === j0})" +
                " EnvcProps.myTargetValue=${e.myTargetValue ?: "<null>"}(same=${e === e0})")
        }
        show("before")
        val keys = setOf("prop.relax.my-target-value", "prop.jb.v", "prop.envc.my-target-value")
        ctx.environment.propertySources.addFirst(MapPropertySource("p19-override", keys.associateWith { "after" as Any }))
        println("[P19] env after addFirst: " + keys.joinToString(" ") { "$it=${ctx.environment.getProperty(it)}" })
        ctx.publishEvent(evtClass.getConstructor(Any::class.java, Set::class.java).newInstance(ctx, keys) as ApplicationEvent)
        show("after EnvironmentChangeEvent")
        println("[P19] rebinder errors=${rebinderClass.getMethod("getErrors").invoke(rebinder)}")
    }
}
