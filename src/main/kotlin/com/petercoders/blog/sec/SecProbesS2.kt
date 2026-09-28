// SPRING-INTERNALS 2편(절 2) — [S2] SecureProbe: 1편 BypassProbe(tx/Probes.kt)와 같은
// 여덟 경로(생성자/@PostConstruct/외부→프록시/this. 자기호출/self필드→프록시/private/protected/final)를
// @PreAuthorize("hasRole('ADMIN')") 로 다시 세운다. 본문은 sec_log 에 한 행만 남긴다.
// 각 경로를 admin(ROLE_ADMIN)과 user(ROLE_USER) 두 권한으로 두 번씩 불러 던져진 예외의
// 실제 클래스 이름과 sec_log 행 수만으로 판정한다(로그 문자열은 증거로 쓰지 않는다).
// 실행: cd ~/Desktop/development/blog && ./gradlew --offline -q secLab --args='S2'
package com.petercoders.blog.sec

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

// create table if not exists — 이 빈의 생성자가 preInstantiateSingletons 중에 곧바로
// jdbc.update 를 부르므로, runS2 가 시작되기(즉 스키마를 따로 준비하기) 전에 테이블이 있어야 한다.
private fun ensureSchemaSecS2(jdbc: JdbcTemplate) {
    jdbc.execute("create table if not exists sec_log(id identity primary key, tag varchar(40), note varchar(40))")
}

private fun rowsSecS2(jdbc: JdbcTemplate, tag: String): Int =
    jdbc.queryForObject("select count(*) from sec_log where tag = ?", Int::class.java, tag) ?: -1

/** [S2] 전용 — BypassProbe(1편)와 같은 여덟 경로를 @PreAuthorize 로 다시 만든 프로브. */
@Service
open class SecureProbe(private val jdbc: JdbcTemplate) {

    @Autowired
    lateinit var self: SecureProbe

    init {
        // 생성자 안: 프록시가 아직 없다 — 인가 검사 자체가 성립하지 않으므로 두 역할 모두 body-ran.
        ensureSchemaSecS2(jdbc)
        jdbc.update("insert into sec_log(tag, note) values ('admin-constructor(init)', 'body-ran')")
        jdbc.update("insert into sec_log(tag, note) values ('user-constructor(init)', 'body-ran')")
    }

    @PostConstruct
    fun afterInit() {
        // BeanPostProcessor 가 프록시로 감싸기 전에 불린다 — 마찬가지로 인가 검사 대상 밖.
        jdbc.update("insert into sec_log(tag, note) values ('admin-@PostConstruct', 'body-ran')")
        jdbc.update("insert into sec_log(tag, note) values ('user-@PostConstruct', 'body-ran')")
    }

    @PreAuthorize("hasRole('ADMIN')")
    fun secured(tag: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", tag)
    }

    /** this. 자기 호출. 프록시를 거치지 않는다. */
    fun selfInvoke(tag: String) = this.secured(tag)

    /** 필드 주입한 자기 자신(= 프록시)을 거친다. */
    fun viaSelfField(tag: String) = self.secured(tag)

    @PreAuthorize("hasRole('ADMIN')")
    private fun privateSecured(tag: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", tag)
    }

    fun callPrivate(tag: String) = privateSecured(tag)

    @PreAuthorize("hasRole('ADMIN')")
    protected open fun protectedSecured(tag: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", tag)
    }

    fun callProtected(tag: String) = self.protectedSecured(tag)

    /** final 은 CGLIB 이 오버라이드하지 못한다 — 프록시 인스턴스에서 불러도 원본이 그대로 실행된다. */
    @PreAuthorize("hasRole('ADMIN')")
    final fun finalSecured(tag: String) {
        jdbc.update("insert into sec_log(tag, note) values (?, 'body-ran')", tag)
    }
}

private fun authS2As(role: String) {
    val auth = if (role == "admin")
        TestingAuthenticationToken("admin", "", "ROLE_ADMIN")
    else
        TestingAuthenticationToken("user", "", "ROLE_USER")
    auth.isAuthenticated = true
    SecurityContextHolder.getContext().authentication = auth
}

private fun rowS2(case: String, auth: String, thrown: String, rows: Int) =
    "| %-24s | %-5s | %-26s | %4d |".format(case, auth, thrown, rows)

fun runS2(ctx: ConfigurableApplicationContext) {
    val jdbc = ctx.getBean(JdbcTemplate::class.java)
    val p = ctx.getBean(SecureProbe::class.java) // 생성자/@PostConstruct 는 이 시점에 이미 실행됐다 — 스키마도 그때 만들어졌다

    println("## [S2] SecureProbe — 1편 BypassProbe와 같은 여덟 경로 x admin/user 두 권한")
    println("| case | auth | thrown | rows |")
    println("|---|---|---|---|")

    for (role in listOf("admin", "user")) {
        println(rowS2("constructor(init)", role, "-", rowsSecS2(jdbc, "$role-constructor(init)")))
    }
    for (role in listOf("admin", "user")) {
        println(rowS2("@PostConstruct", role, "-", rowsSecS2(jdbc, "$role-@PostConstruct")))
    }

    val cases: List<Pair<String, (String) -> Unit>> = listOf(
        "external → proxy" to { tag: String -> p.secured(tag) },
        "self-invocation this." to { tag: String -> p.selfInvoke(tag) },
        "self field → proxy" to { tag: String -> p.viaSelfField(tag) },
        "private" to { tag: String -> p.callPrivate(tag) },
        "protected" to { tag: String -> p.callProtected(tag) },
        "final" to { tag: String -> p.finalSecured(tag) },
    )

    for ((label, call) in cases) {
        for (role in listOf("admin", "user")) {
            authS2As(role)
            val tag = "$role-$label"
            val thrown = try {
                call(tag); "-"
            } catch (e: Throwable) {
                e.javaClass.simpleName
            }
            println(rowS2(label, role, thrown, rowsSecS2(jdbc, tag)))
        }
    }
}
