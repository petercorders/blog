// SPRING-INTERNALS 4편 [P12] — 테스트에서 같은 키를 여러 곳에 넣었을 때 어느 것이 이기는가.
// 공용 test 설정은 건드리지 않는다. 전용 Test 태스크 propTestLab 이 진짜 -D(systemProperty)와
// 진짜 OS env(environment)를 넣고 이 파일의 PropTestLab* 클래스만 돈다.
//   ./gradlew --offline propTestLab --console=plain
// 클래스마다 소스를 하나씩 뺀다. 각 테스트는 PropertySource 목록 전체와 key 를 가진 소스를 찍는다.
package com.petercoders.blog.prop

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource

private const val P12_KEY = "prop.lab.value"
private const val TPS_LOCATION = "classpath:com/petercoders/blog/prop/proptestlab.properties"

@Configuration(proxyBeanMethods = false)
open class PropTestLabConfig

private fun report(tag: String, env: ConfigurableEnvironment) {
    println("[P12 $tag] -D prop.lab.value=${System.getProperty(P12_KEY)} OS PROP_LAB_VALUE=${System.getenv("PROP_LAB_VALUE")}")
    env.propertySources.forEachIndexed { i, ps ->
        val held = if (ps.containsProperty(P12_KEY)) " holds=${ps.getProperty(P12_KEY)}" else ""
        println("[P12 $tag][%02d] %s :: %s%s".format(i, ps.name, ps.javaClass.simpleName, held))
    }
    println("[P12 $tag] getProperty=${env.getProperty(P12_KEY)}")
}

/** T1: @DynamicPropertySource + @TestPropertySource(properties, locations) + @SpringBootTest(properties) */
@SpringBootTest(classes = [PropTestLabConfig::class], properties = ["$P12_KEY=fromSpringBootTestProperties"])
@TestPropertySource(properties = ["$P12_KEY=fromTestPropertySourceProperties"], locations = [TPS_LOCATION])
class PropTestLabT1All {
    @Autowired lateinit var env: ConfigurableEnvironment

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dynamic(registry: DynamicPropertyRegistry) {
            registry.add(P12_KEY) { "fromDynamicPropertySource" }
        }
    }

    @Test fun dump() = report("T1 all", env)
}

/** T2: T1 에서 @DynamicPropertySource 를 뺀다 */
@SpringBootTest(classes = [PropTestLabConfig::class], properties = ["$P12_KEY=fromSpringBootTestProperties"])
@TestPropertySource(properties = ["$P12_KEY=fromTestPropertySourceProperties"], locations = [TPS_LOCATION])
class PropTestLabT2NoDynamic {
    @Autowired lateinit var env: ConfigurableEnvironment
    @Test fun dump() = report("T2 -dynamic", env)
}

/** T3: @TestPropertySource(properties) 도 뺀다 → @SpringBootTest(properties) + @TestPropertySource(locations) */
@SpringBootTest(classes = [PropTestLabConfig::class], properties = ["$P12_KEY=fromSpringBootTestProperties"])
@TestPropertySource(locations = [TPS_LOCATION])
class PropTestLabT3SbtAndLocations {
    @Autowired lateinit var env: ConfigurableEnvironment
    @Test fun dump() = report("T3 sbt+locations", env)
}

/** T4: @TestPropertySource(properties) 와 @SpringBootTest(properties) 둘만 (같은 inlined 소스로 들어가는가) */
@SpringBootTest(classes = [PropTestLabConfig::class], properties = ["$P12_KEY=fromSpringBootTestProperties"])
@TestPropertySource(properties = ["$P12_KEY=fromTestPropertySourceProperties"])
class PropTestLabT4SbtAndTpsProperties {
    @Autowired lateinit var env: ConfigurableEnvironment
    @Test fun dump() = report("T4 sbt+tps-properties", env)
}

/** T5: @SpringBootTest(properties) 하나 */
@SpringBootTest(classes = [PropTestLabConfig::class], properties = ["$P12_KEY=fromSpringBootTestProperties"])
class PropTestLabT5SbtOnly {
    @Autowired lateinit var env: ConfigurableEnvironment
    @Test fun dump() = report("T5 sbt-only", env)
}

/** T6: @TestPropertySource(locations) 하나 */
@SpringBootTest(classes = [PropTestLabConfig::class])
@TestPropertySource(locations = [TPS_LOCATION])
class PropTestLabT6LocationsOnly {
    @Autowired lateinit var env: ConfigurableEnvironment
    @Test fun dump() = report("T6 locations-only", env)
}

/** T7: 애노테이션 값 없음 → 태스크가 넣은 -D 와 OS env 만 */
@SpringBootTest(classes = [PropTestLabConfig::class])
class PropTestLabT7None {
    @Autowired lateinit var env: ConfigurableEnvironment
    @Test fun dump() = report("T7 none", env)
}
