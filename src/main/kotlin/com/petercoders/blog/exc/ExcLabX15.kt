// SPRING-INTERNALS 3편 [X15] — 같은 요청을 MockMvc 와 실제 서버(RestClient)로 보내 결과를 나란히 찍는다.
// spring-test 는 excLabExtra configuration 에만 있다(build.gradle.kts). excLab 태스크로만 돈다.
package com.petercoders.blog.exc

import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

private fun mock(mvc: MockMvc, method: HttpMethod, path: String): String =
    try {
        val res = mvc.perform(MockMvcRequestBuilders.request(method, path)).andReturn().response
        "status=${res.status} content-type=${res.contentType ?: "-"} errorMessage=${res.errorMessage} " +
            "body='${res.contentAsString.replace("\n", " ").take(200)}'"
    } catch (e: Throwable) {
        "perform() threw ${e.javaClass.name}: ${e.message?.take(160)}" +
            (e.cause?.let { " / cause=${it.javaClass.name}: ${it.message}" } ?: "")
    }

internal fun runX15() {
    envHeader("X15")
    println("advice 없는 맨 컨텍스트. MockMvc 는 같은 WebApplicationContext 로 만들고 TraceFilter·BoomFilter 를 addFilters 로 붙인다.")
    boot().use { ctx ->
        schema(ctx)
        val mvc = MockMvcBuilders.webAppContextSetup(ctx as WebApplicationContext)
            .addFilters<DefaultMockMvcBuilder>(TraceFilter(), BoomFilter())
            .build()
        for ((label, m, p) in listOf(
            Triple("Filter 예외 GET /x5/filter-boom", HttpMethod.GET, "/x5/filter-boom"),
            Triple("405 POST /x2/ise", HttpMethod.POST, "/x2/ise"),
            Triple("400 GET /x4/param", HttpMethod.GET, "/x4/param"),
            Triple("(추가) advice 없는 ISE GET /x2/ise", HttpMethod.GET, "/x2/ise"),
        )) {
            println()
            println("### $label")
            println("  MockMvc     : ${mock(mvc, m, p)}")
            val r = call(m, p)
            println("  실제 서버   : status=${r.status} content-type=${r.contentType} body='${r.body.replace("\n", " ").take(200)}'")
        }
    }
}
