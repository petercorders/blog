// SPRING-INTERNALS 2편 — [S13] 무권한 사용자 테스트가 무엇을 잡고 무엇을 못 잡나.
// @WithMockUser(roles = ["USER"]) 로 secured()(외부 → Proxy)와 selfInvoke()(this. 자기 호출)에 각각
// assertThrows<AccessDeniedException> 를 건다. 기대: secured 는 통과(예외가 난다), selfInvoke 는 실패(예외가 안 난다).
// selfInvoke 쪽은 일부러 실패하는 시연이라 SEC_S13=1 일 때만 돈다(평소 ./gradlew test 를 깨지 않게).
// 실행: SEC_S13=1 ./gradlew --offline test --tests 'com.petercoders.blog.sec.SecS13Test'
package com.petercoders.blog.sec

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.context.support.WithMockUser

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SEC_S13", matches = "1")
@SpringBootTest(
    classes = [SecLabApp::class],
    properties = [
        "spring.main.web-application-type=none",
        "spring.main.allow-circular-references=true",
        "spring.datasource.url=jdbc:h2:mem:seclab-s13;DB_CLOSE_DELAY=-1",
    ],
)
class SecS13Test {
    @Autowired
    lateinit var probe: SecureProbe

    companion object {
        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun runtime() = println("# runtime: java.runtime.version=${System.getProperty("java.runtime.version")} java.vendor=${System.getProperty("java.vendor")}")
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `secured - USER 는 거부된다`() {
        assertThrows<AccessDeniedException> { probe.secured("s13-secured") }
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `selfInvoke - USER 는 거부된다`() {
        assertThrows<AccessDeniedException> { probe.selfInvoke("s13-self") }
    }
}
