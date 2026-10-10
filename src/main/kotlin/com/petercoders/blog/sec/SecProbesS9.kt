// SPRING-INTERNALS 2편 — #id 파라미터 이름은 컴파일러가 확인하지 않는다.
// [S9b] Kotlin(Boot Gradle 플러그인 -java-parameters + kotlin-reflect) 앱에서 파라미터 이름을 바꾸고 SpEL 은 그대로 둔 경우.
// [S9]  -parameters 없이 javac 로 따로 컴파일한 Java 클래스(@P 유무)를 URLClassLoader 로 올려 같은 식을 평가.
//       컴파일 명령과 javap 결과는 runs/s9.log 머리에 적는다. 클래스 디렉터리는 인자 두 번째로 받는다.
// 실행: ./gradlew --offline -q secLab --args='S9b'   /   ./gradlew --offline -q secLab --args='S9 <classesDir>'
package com.petercoders.blog.sec

import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import java.io.File
import java.net.URLClassLoader

@Service
class ParamNameProbe(private val jdbc: JdbcTemplate) {
    /** 파라미터를 id -> orderId 로 바꾸고 SpEL 은 그대로 둔 경우. */
    @PreAuthorize("#id == authentication.name")
    fun byOrderId(orderId: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", "s9b-byOrderId")
    }

    /** 부정형 식. #id 가 null 이면 null != 'blocked' 가 true 다. */
    @PreAuthorize("#id != 'blocked'")
    fun negated(orderId: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", "s9b-negated")
    }

    /** 대조군: 이름이 맞는 경우. */
    @PreAuthorize("#id == authentication.name")
    fun byId(id: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", "s9b-byId")
    }
}

fun runS9b(ctx: ConfigurableApplicationContext) {
    println("## [S9b] Kotlin 파라미터 이름 불일치 — alice(ROLE_USER) 로 호출")
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    ensureSecLog(jdbc)
    authAs("alice", "ROLE_USER")
    val p = ctx.getBean(ParamNameProbe::class.java)
    println("byOrderId(orderId=\"alice\") [#id == authentication.name] -> thrown=${thrownOf { p.byOrderId("alice") }} rows=${secRows(jdbc, "s9b-byOrderId")}")
    println("negated(orderId=\"blocked\") [#id != 'blocked']           -> thrown=${thrownOf { p.negated("blocked") }} rows=${secRows(jdbc, "s9b-negated")}")
    println("byId(id=\"alice\") [#id == authentication.name]           -> thrown=${thrownOf { p.byId("alice") }} rows=${secRows(jdbc, "s9b-byId")}")
}

/** [S9] -parameters 없이 컴파일된 Java 클래스를 자식 ClassLoader 로 올려 Bean 으로 등록한다. */
fun runS9(classesDir: String) {
    println("## [S9] -parameters 없이 javac 로 컴파일한 Java 클래스 — alice(ROLE_USER) 로 id=\"alice\" 호출")
    val cl = URLClassLoader(arrayOf(File(classesDir).toURI().toURL()), SecLabApp::class.java.classLoader)
    val target = cl.loadClass("com.petercoders.blog.secx.s9.S9Target")
    println("S9Target loaded by ${target.classLoader.javaClass.simpleName} from ${target.protectionDomain.codeSource?.location}")
    for (m in target.declaredMethods.sortedBy { it.name }) {
        println("reflection: ${m.name} parameter[0].isNamePresent=${m.parameters[0].isNamePresent} name=${m.parameters[0].name}")
    }
    val ctx = SpringApplicationBuilder(SecLabApp::class.java)
        .resourceLoader(DefaultResourceLoader(cl))
        .initializers(org.springframework.context.ApplicationContextInitializer<ConfigurableApplicationContext> { c ->
            (c as org.springframework.context.support.GenericApplicationContext)
                .registerBeanDefinition("s9Target", org.springframework.beans.factory.support.RootBeanDefinition(target))
        })
        .properties(
            "spring.main.web-application-type=none", "spring.main.banner-mode=off",
            "spring.datasource.url=jdbc:h2:mem:seclab-s9;DB_CLOSE_DELAY=-1",
            "logging.level.root=WARN", "spring.main.allow-circular-references=true",
        ).run()
    try {
        authAs("alice", "ROLE_USER")
        val bean = ctx.getBean("s9Target")
        println("bean class = ${bean.javaClass.name}")
        for (name in listOf("byId", "byIdWithP")) {
            val m = bean.javaClass.getMethod(name, String::class.java)
            val r = try { "returned ${m.invoke(bean, "alice")}" } catch (e: java.lang.reflect.InvocationTargetException) { "threw ${e.targetException.javaClass.name}" }
            println("$name(\"alice\") -> $r")
        }
    } finally {
        ctx.close()
    }
}
