// SPRING-INTERNALS 2편 — [S1b] 컨테이너에 등록된 Advisor 다섯과, Bean마다 실제 Proxy가 든 체인.
// AllAdvicesProbe는 다섯 애노테이션과 @Transactional을 메서드에 나눠 단 전용 프로브다(호출하지 않고 체인만 본다).
// 실행: ./gradlew --offline -q secLab --args='S1b'
package com.petercoders.blog.sec

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.access.prepost.PostFilter
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.access.prepost.PreFilter
import org.springframework.security.authorization.method.AuthorizeReturnObject
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AllAdvicesProbe {
    @PreFilter("true")
    fun preFilter(items: MutableList<String>): Int = items.size

    @PreAuthorize("true")
    fun pre(): String = "ok"

    @AuthorizeReturnObject
    fun returnObject(): Account = Account("x")

    @PostAuthorize("true")
    fun post(): String = "ok"

    @PostFilter("true")
    fun postFilter(): MutableList<String> = mutableListOf()

    @Transactional
    fun tx(): String = "ok"
}

fun runS1b(ctx: ConfigurableApplicationContext) {
    println("## [S1b] Bean마다 실제 Advisor 체인 — (ctx.getBean(X::class.java) as Advised).advisors")
    println()
    printChain("AllAdvicesProbe", ctx.getBean(AllAdvicesProbe::class.java), ctx)
    println()
    printChain("OrderProbe", ctx.getBean(OrderProbe::class.java), ctx)
    println()
    printChain("SecureProbe", ctx.getBean(SecureProbe::class.java), ctx)
    println()
    printChain("TxOrderProbe", ctx.getBean(TxOrderProbe::class.java), ctx)
    println()
    val all = (ctx.getBean(AllAdvicesProbe::class.java) as org.springframework.aop.framework.Advised).advisors
    val orders = all.mapNotNull { (it as? org.springframework.core.Ordered)?.order }
    println("AllAdvicesProbe: advisors=${all.size}, distinct orders=${orders.toSet().size}, duplicated order present=${orders.size != orders.toSet().size}")
}
