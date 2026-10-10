// SPRING-INTERNALS 2편 — ③ 목록은 데이터 계층에서 거른다.
// [S5d] @PostFilter 식 안에서 @bean 으로 JdbcTemplate 조회를 부르면 원소마다 쿼리가 나가는가.
//       횟수는 SpEL 카운터가 아니라 DB 쪽에서 센다: H2 의 SET QUERY_STATISTICS TRUE 뒤
//       INFORMATION_SCHEMA.QUERY_STATISTICS 의 SQL 문별 EXECUTION_COUNT.
//       대조군은 같은 결과를 where 절(join) 한 번으로 읽는 메서드다. 문서 n = 0/3/10개, alice 는 짝수 번만 읽을 수 있다.
// 실행: ./gradlew --offline -q secLab --args='S5d'
package com.petercoders.blog.sec

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PostFilter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/** @PostFilter 식에서 @docAcl.canRead(...) 로 부르는 권한 조회 Bean. 부를 때마다 쿼리 하나를 낸다. */
@Component("docAcl")
class DocAcl(private val jdbc: JdbcTemplate) {
    fun canRead(docId: String, user: String): Boolean =
        (jdbc.queryForObject(
            "select count(*) from doc_acl where doc_id = ? and username = ?",
            Int::class.java, docId, user) ?: 0) > 0
}

@Service
class DocListProbe(private val jdbc: JdbcTemplate) {
    /** 목록을 다 읽은 뒤 원소마다 권한 조회 Bean 을 부른다. */
    @PostFilter("@docAcl.canRead(filterObject, authentication.name)")
    fun listAll(): MutableList<String> =
        jdbc.query("select id from doc order by id") { rs, _ -> rs.getString(1) }

    /** 대조군: 볼 수 있는 행만 쿼리 조건으로 읽는다. */
    fun listReadable(user: String): List<String> =
        jdbc.query(
            "select d.id from doc d join doc_acl a on a.doc_id = d.id where a.username = ? order by d.id",
            { rs, _ -> rs.getString(1) }, user)
}

private fun resetQueryStats(jdbc: JdbcTemplate) {
    jdbc.execute("SET QUERY_STATISTICS FALSE") // H2 는 끌 때 통계를 버린다
    jdbc.execute("SET QUERY_STATISTICS TRUE")
}

/** doc 테이블을 건드린 SQL 문별 실행 횟수. 통계 조회·SET 문 자신은 뺀다. */
private fun docQueryStats(jdbc: JdbcTemplate): List<Pair<String, Int>> =
    jdbc.query(
        "select SQL_STATEMENT, EXECUTION_COUNT from INFORMATION_SCHEMA.QUERY_STATISTICS " +
            "where lower(SQL_STATEMENT) like '%doc%' and SQL_STATEMENT not like '%QUERY_STATISTICS%' " +
            "order by SQL_STATEMENT",
    ) { rs, _ -> rs.getString(1) to rs.getInt(2) }

private fun printStats(label: String, returned: List<String>, stats: List<Pair<String, Int>>) {
    println("$label -> returned ${returned.size} $returned")
    stats.forEach { (sql, count) -> println("    executions=$count  $sql") }
    println("    total DB executions = ${stats.sumOf { it.second }}")
}

fun runS5d() {
    println("## [S5d] @PostFilter 식 안의 @bean 조회 — DB 쪽 실행 횟수(H2 QUERY_STATISTICS)")
    val ctx = bootSec("seclab_s5d")
    try {
        val jdbc = ctx.getBean(JdbcTemplate::class.java)
        println("H2 version = ${jdbc.queryForObject("select H2VERSION()", String::class.java)}")
        jdbc.execute("create table doc(id varchar(20) primary key)")
        jdbc.execute("create table doc_acl(doc_id varchar(20), username varchar(20))")
        val p = ctx.getBean(DocListProbe::class.java)
        for (n in listOf(0, 3, 10)) {
            jdbc.update("delete from doc_acl")
            jdbc.update("delete from doc")
            for (i in 0 until n) {
                jdbc.update("insert into doc(id) values (?)", "d$i")
                if (i % 2 == 0) jdbc.update("insert into doc_acl(doc_id, username) values (?, 'alice')", "d$i")
            }
            authAs("alice", "ROLE_USER")
            println()
            println("[n=$n] doc rows = $n, alice readable = ${(n + 1) / 2}")

            resetQueryStats(jdbc)
            val filtered = p.listAll()
            printStats("  @PostFilter listAll()", filtered, docQueryStats(jdbc))

            resetQueryStats(jdbc)
            val byWhere = p.listReadable("alice")
            printStats("  where 절 listReadable(alice)", byWhere, docQueryStats(jdbc))
        }
    } finally {
        SecurityContextHolder.clearContext()
        ctx.close()
    }
}
