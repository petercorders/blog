// SPRING-INTERNALS 3편(절 3) — [S4b] @Transactional 을 어드바이저 체인의 바깥으로 미는 설정.
// order=0 은 postAuthorizeAuthorizationAdvisor(500)보다 작으므로 트랜잭션 어드바이저가 바깥이 된다.
// @EnableTransactionManagement 를 직접 선언하면 Boot 의 TransactionAutoConfiguration 이
// @ConditionalOnMissingBean 이라 물러난다 — 물러나면서 Boot 가 기본으로 켜 주던
// proxyTargetClass=true 가 함께 사라지므로 여기서 다시 명시해 1편과 같은 CGLIB 프록시를 유지한다.
// 1편의 allexc(AllExceptionsRollbackConfig, @Profile("allexc"))와 같은 패턴.
// 실행: cd ~/Desktop/development/blog && ./gradlew --offline -q secLab --args='S4b'
package com.petercoders.blog.sec

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.transaction.annotation.EnableTransactionManagement

@Configuration(proxyBeanMethods = false)
@Profile("txouter")
@EnableTransactionManagement(order = 0, proxyTargetClass = true)
class TxOuterConfig
