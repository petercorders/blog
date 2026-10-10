// SPRING-INTERNALS 2편 — [S4b]·[S3b]·[S4e]·[S7] @Transactional 을 Advisor 체인의 바깥으로 미는 설정.
// order=0 은 postAuthorizeAuthorizationAdvisor(500)보다 작으므로 Transaction Advisor 가 바깥이 된다.
// @EnableTransactionManagement 를 직접 선언하면 Boot 의 TransactionAutoConfiguration 쪽 설정은 물러나지만,
// proxyTargetClass 를 적지 않아도 CGLIB 은 유지된다: AopAutoConfiguration.ClassProxyingConfiguration 이
// spring.aop.proxy-target-class(matchIfMissing = true)일 때 forceAutoProxyCreatorToUseClassProxying 을 한다
// (spring-boot v4.1.1 소스, aspectjweaver 가 없는 이 저장소 기준). 여기서 proxyTargetClass = true 를 적은 것은 의도를 남기려는 것이다.
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
