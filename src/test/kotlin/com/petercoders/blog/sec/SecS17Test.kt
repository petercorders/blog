// SPRING-INTERNALS 2편 — [S17] #15254 가 닫힌 뒤 올라온 Boot 4.0.0-RC2 재현 샘플(github.com/rbetjes/spring-security-issue-15254-demo)의
// 500 이 어디서 오는지. 샘플의 설정을 그대로 옮겼다: anonymous 끔, /test 는 authenticated, 나머지는 denyAll,
// ResponseEntityExceptionHandler 를 상속만 한 @ControllerAdvice, @PreAuthorize("hasRole('ROLE_ADMIN')") 컨트롤러.
// 같은 GET 을 세 가지 클라이언트로 보낸다.
//   (a) 샘플 테스트와 같은 RestTestClient.bindToApplicationContext(ctx) — MockMvc 에 Security Filter 를 붙이지 않는다
//   (b) MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()) 로 만든 MockMvc 에 RestTestClient.bindTo
//   (c) 실제 서버(RANDOM_PORT)에 RestTestClient.bindToServer
// 판정은 응답 상태 코드와 (500 이면) 본문에 찍힌 예외 클래스로만 한다. 단언 없이 결과만 찍는다.
// 앱·컨트롤러·advice 는 이 테스트 클래스 안에 중첩해 둔다. TestTypeExcludeFilter 가 테스트 클래스의 중첩 클래스를
// 스캔에서 빼므로 SecS13Test(SecLabApp 스캔)·BlogApplicationTests 컨텍스트에 섞이지 않는다.
// 실행: SEC_S17=1 ./gradlew --offline test --tests 'com.petercoders.blog.sec.SecS17Test'
package com.petercoders.blog.sec

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@EnabledIfEnvironmentVariable(named = "SEC_S17", matches = "1")
@SpringBootTest(
    classes = [SecS17Test.S17App::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:seclab-s17;DB_CLOSE_DELAY=-1",
        "spring.main.banner-mode=off",
        "logging.level.root=WARN",
    ],
)
@TestMethodOrder(MethodOrderer.MethodName::class)
class SecS17Test {
    @Autowired
    lateinit var ctx: WebApplicationContext

    @LocalServerPort
    var port: Int = 0

    companion object {
        @JvmStatic
        @BeforeAll
        fun runtime() = println("# runtime: java.runtime.version=${System.getProperty("java.runtime.version")} java.vendor=${System.getProperty("java.vendor")}")
    }

    private fun report(label: String, client: RestTestClient) {
        val r = client.get().uri("/s17/test").exchange().returnResult(String::class.java)
        val body = r.responseBody?.replace("\n", " ")?.take(220) ?: "-"
        println("[S17] $label -> status=${r.status.value()} body='$body'")
    }

    private fun noFilter(): RestTestClient = RestTestClient.bindToApplicationContext(ctx).build()

    private fun withFilter(): RestTestClient = RestTestClient.bindTo(
        MockMvcBuilders.webAppContextSetup(ctx)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build(),
    ).build()

    private fun server(): RestTestClient = RestTestClient.bindToServer().baseUrl("http://localhost:$port").build()

    @Test
    fun `a1 Filter 없는 MockMvc, 인증 없음`() = report("(a) bindToApplicationContext, 인증 없음", noFilter())

    @Test
    @WithMockUser(roles = ["USER"])
    fun `a2 Filter 없는 MockMvc, USER`() = report("(a) bindToApplicationContext, @WithMockUser USER", noFilter())

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `a3 Filter 없는 MockMvc, ADMIN`() = report("(a) bindToApplicationContext, @WithMockUser ADMIN", noFilter())

    @Test
    fun `b1 springSecurity MockMvc, 인증 없음`() = report("(b) MockMvc + springSecurity(), 인증 없음", withFilter())

    @Test
    @WithMockUser(roles = ["USER"])
    fun `b2 springSecurity MockMvc, USER`() = report("(b) MockMvc + springSecurity(), @WithMockUser USER", withFilter())

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `b3 springSecurity MockMvc, ADMIN`() = report("(b) MockMvc + springSecurity(), @WithMockUser ADMIN", withFilter())

    @Test
    fun `c1 실제 서버, 인증 없음`() = report("(c) 실제 서버(RANDOM_PORT), 인증 없음", server())

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMethodSecurity
    @EnableWebSecurity
    @Import(S17Controller::class, S17Advice::class)
    class S17App {
        @Bean
        fun s17FilterChain(http: HttpSecurity): SecurityFilterChain {
            http
                .csrf { it.disable() }
                .cors { it.disable() }
                .anonymous { it.disable() }
                .authorizeHttpRequests { it.requestMatchers("/s17/test").authenticated().anyRequest().denyAll() }
            return http.build()
        }
    }

    @RestController
    class S17Controller {
        @GetMapping("/s17/test")
        @PreAuthorize("hasRole('ROLE_ADMIN')")
        fun test(): String = "TEST"
    }

    @ControllerAdvice
    class S17Advice : ResponseEntityExceptionHandler()
}
