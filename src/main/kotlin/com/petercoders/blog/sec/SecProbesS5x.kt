// SPRING-INTERNALS 2편 — ③ 돌려받는 리스트가 원본인지 복사본인지.
// [S5b] 읽기 전용 리스트(Kotlin listOf())를 필드에 들고 그대로 반환 — 예외 여부, 반환 크기, 반환 === 입력, 입력 크기.
// [S5c] 크기 10 가변 리스트를 필드에 들고 그대로 반환, 절반 통과 — 반환 크기, 반환 === 공유 참조, 공유 크기, 평가 횟수.
// 6.3 이하 동작은 Boot 4 의존과 맞지 않아 실행하지 않는다(6.3.0/6.4.0 소스 태그 대조로만 쓴다).
// 실행: ./gradlew --offline -q secLab --args='S5b' / 'S5c'
package com.petercoders.blog.sec

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.access.prepost.PostFilter
import org.springframework.stereotype.Service

@Service
class ListIdentityProbe {
    /** Kotlin listOf() = 읽기 전용(고정 크기) 리스트. */
    val ro: List<String> = listOf("e0", "e1", "e2")

    /** 가변 리스트. k0, d1, k2, d3 ... 로 짝수 칸만 'k'라 절반이 통과한다. */
    val shared: MutableList<String> = MutableList(10) { if (it % 2 == 0) "k$it" else "d$it" }

    @PostFilter("filterObject != 'e1'")
    fun readOnly(): List<String> = ro

    @PostFilter("@evalCounter.mark('s5c') and filterObject.startsWith('k')")
    fun sharedList(): MutableList<String> = shared
}

fun runS5b(ctx: ConfigurableApplicationContext) {
    println("## [S5b] @PostFilter + Kotlin listOf() 필드 반환 (Security ${org.springframework.security.core.SpringSecurityCoreVersion.getVersion()})")
    val p = ctx.getBean(ListIdentityProbe::class.java)
    val input = p.ro // Proxy가 target에 위임한 getter라 target의 필드 참조다
    println("input class = ${input.javaClass.name}, input size before = ${input.size}")
    try {
        val out = p.readOnly()
        println("exception = none")
        println("returned = $out, returned size = ${out.size}, returned class = ${out.javaClass.name}")
        println("returned === input : ${out === input}")
        println("input size after = ${p.ro.size}")
    } catch (e: Throwable) {
        println("exception = ${e.javaClass.name}: ${e.message}")
    }
}

fun runS5c(ctx: ConfigurableApplicationContext) {
    println("## [S5c] @PostFilter + 가변 리스트 필드 반환, 10개 중 절반 통과")
    val counter = ctx.getBean(EvalCounter::class.java)
    counter.reset()
    val p = ctx.getBean(ListIdentityProbe::class.java)
    val shared = p.shared
    println("shared size before = ${shared.size}")
    val out = p.sharedList()
    println("returned size = ${out.size}")
    println("returned === shared : ${out === shared}")
    println("shared size after = ${p.shared.size}")
    println("spel evals = ${counter.counts["s5c"]?.get() ?: 0}")
}
