package com.petercoders.blog.tx

import org.springframework.transaction.annotation.Transactional

/**
 * all-open 플러그인의 효과만 보기 위한 최소 클래스. 빈으로 등록되지 않는다.
 * kotlinc 로 두 번 컴파일해 javap -p 의 final 유무를 비교한다.
 *   (a) 플러그인 없이           → class 도 fun 도 final
 *   (b) -Xplugin=allopen ...    → class 와 기본 modality 메서드만 open, 명시적 final 은 그대로
 */
@Transactional
class AllOpenProbe {
    fun defaultModality(): String = "opened by the plugin"
    final fun explicitFinal(): String = "still final"
    private fun invisible(): String = "never opened"
    fun callInvisible(): String = invisible()
}

/** @Transactional 이 없으면 프리셋 대상이 아니다. */
class NotAnnotated {
    fun stillFinal(): String = "not a preset target"
}
