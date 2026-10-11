# prop — 설정값은 파일이 아니라 순서·이름·시점이 정한다 (실험 코드)

이 디렉터리는 velog 글 [설정값은 파일이 아니라 순서·이름·시점이 정한다](https://velog.io/@petercoders)(SPRING-INTERNALS 4편, 게시 후 링크로 교체)의 실측 코드다. 글 본문에는 결정적 출력 몇 줄만 실었고, 전체 코드와 로그는 여기에 있다.

## 실행 환경

아래 값은 전부 `runs/P1.log`(E1) 머리와 `env:` 줄에 찍힌 값이다. 로그에 없는 값은 적지 않는다.

| 항목 | 값 | 근거 |
|---|---|---|
| 기기 | Apple M5 Pro, `hw.ncpu=15`, macOS 26.6.2 | `P1.log` `# machine` |
| 앱을 실행한 JVM | OpenJDK 25.0.4.1+1-LTS, `java.vendor=Microsoft` (Gradle toolchain `JavaLanguageVersion.of(25)`) | `P1.log` `env:` 줄, `# java -version` |
| 셸 기본 `java` | OpenJDK 26.0.2.1 (build 26.0.2.1+1-7). 앱 실행에는 쓰지 않는다 | `P1.log` `# java -version` |
| Kotlin | 2.3.21 | `env:` 줄 `kotlin=` |
| Spring Boot | 4.1.1 | `env:` 줄 `spring-boot=` |
| Spring Framework | 7.0.9 | `env:` 줄 `spring-framework=` |
| Spring Security | 7.1.1 (main 클래스패스에 있다. 4편 실험은 P13에서만 쓴다) | `env:` 줄 `spring-security=` |

절대 수치(기동 시간 등)는 이 기기에 종속된다. 이 폴더의 결론은 소스 목록의 상대 순서와 승자, 기동 성공·실패뿐이라 기기와 무관하다.

## 실험 목록

'글의 절'은 원고(`4-PROPERTY-RESOLUTION-POST.md`)의 절 제목 그대로다. ①·②·③ 은 문제별 절(① 덮을 값은 실행 시점에 넣고, 기본값은 맨 아래 칸에 둔다 / ② 환경 변수와 @Value는 정해진 표기에서만 같은 키가 된다 / ③ 바인딩은 Bean을 만들 때 한 번뿐이다)이고, `›` 뒤가 하위 절이다. 괄호는 그 절에서 이 실험이 맡은 부분이다. '코드'의 파일은 이 디렉터리 기준이고, P12 만 `src/test/` 아래에 있다.

| 마커 | 무엇을 보나 | 결과 한 줄 | 글의 절 | 코드 | 로그 |
|---|---|---|---|---|---|
| E1·P1 | 이 앱의 `PropertySource` 목록 전체(이름 전체)와 실행 환경 | 6칸, 0번은 `configurationProperties`(값 없는 창구), servlet 소스 없음 | 도입(실험 환경 문단)<br>설정값 하나는 PropertySource 목록의 첫 일치가 정한다 | `PropLab.kt` `runP1`·`envLine` | `P1.log` |
| P2 | 프로파일 off/dev 에서 끼어드는 소스 자리 | dev 를 켜면 `application-dev.yml` 이 `application.yml` 바로 위에 끼어 이긴다 | ① › 설정 파일끼리는 Profile, import, 위치, 문서 순서가 정한다<br>① › @PropertySource와 defaultProperties는 목록 맨 아래 두 칸이다(덤프 끝 `defaultProperties`) | `PropLab.kt` `runP2` | `P2.log` |
| P3 | `spring.config.import` 문서와 선언한 문서의 상대 위치 | import 파일이 선언한 파일 바로 위에 붙어 이긴다 | ① › 설정 파일끼리는 Profile, import, 위치, 문서 순서가 정한다<br>① › @PropertySource와 defaultProperties는 목록 맨 아래 두 칸이다(덤프 끝 `defaultProperties`) | `PropLab.kt` `runP3` | `P3.log` |
| P4' | 진짜 커맨드라인·`-D`·OS env·프로파일 파일·configtree·import·`application.yml`·`@PropertySource`·defaultProperties 서열, 세 시점 덤프, initializer `addFirst`, P16b | 커맨드라인 > `-D` > OS env > 프로파일 파일 > import 두 개(목록에 뒤에 쓴 configtree 가 앞에 쓴 external.yml 위) > `application.yml` > `@PropertySource` > defaultProperties. `@PropertySource` 는 prepared 이벤트엔 없고 refresh 중에 defaultProperties 바로 위로 붙는다. initializer `addFirst` 는 `configurationProperties` 보다도 앞(00)에 선다. `@PropertySource` 안의 `spring.profiles.active=dev` 는 프로파일을 켜지 못한다 | ① › 커맨드라인, -D, 환경 변수는 모든 설정 파일을 이긴다(서열, initializer `addFirst`)<br>① › 설정 파일끼리는 Profile, import, 위치, 문서 순서가 정한다(import 두 개의 순서)<br>① › @PropertySource와 defaultProperties는 목록 맨 아래 두 칸이다(세 시점 덤프, P16b) | `PropLabPlus.kt` `runP4Clean`(옛 P4 는 `PropLab.kt` `runP4`) | `P4.log` (옛 P4 는 `P4-legacy.log`) |
| P4b | jar 밖(`file:./`, `./config/`) 대 jar 안(classpath) | 밖 `application.yml` 이 안 `application-dev.yml` 을 이긴다. location 을 세미콜론 한 그룹으로 주면 뒤집힌다 | ① › 설정 파일끼리는 Profile, import, 위치, 문서 순서가 정한다 | `PropLabPlus.kt` `runP4b` | `P4b.log` |
| P5 | 파일 키 네 표기 | 넷 다 붙는다 | ② › 대문자 접두사에 소문자 snake를 붙인 환경 변수는 조용히 null이 된다(파일 쪽 대조) | `PropLab.kt` `runP5` | `P5.log` |
| P6 | 환경 변수 네 표기 + 후보 | `PROP_RELAX_my_target_value` 만 null | ② › 대문자 접두사에 소문자 snake를 붙인 환경 변수는 조용히 null이 된다<br>② › 환경 변수 이름은 점을 밑줄로 바꾸고 대시는 지우고 대문자로 쓴다(Atlassian 표기 문단) | `PropLab.kt` `runP6` | `P6.log` |
| P7 | 같은 파일 키를 `@Value`(kebab·camel)와 `@ConfigurationProperties` 가 읽을 때, PSPC 유무 | 파일 키가 kebab 이면 `@Value(camel)` 만 못 찾는다. PSPC 유무와 무관 | ② › @Value 키는 소문자 kebab-case로 쓴다 | `PropLab.kt` `runP7` | `P7.log` |
| P8 | 기동 뒤 `addFirst` 했을 때 `Environment` 와 Bean 필드 | `Environment` 는 after, Bean 은 before | ③ › Environment는 바뀌어도 Bean 필드는 그대로다 | `PropLab.kt` `runP8` | `P8.log` |
| P9·P9b | 두 프로파일 순서, 멀티 문서, 프로파일 파일 안 활성화, 옛 키 `spring.profiles` | 프로파일 파일은 뒤에 쓴 프로파일이, 멀티 문서는 아래 문서가 이긴다. 프로파일 파일 안 `spring.profiles.active` 와 옛 키 `spring.profiles` 는 `InvalidConfigDataPropertyException` 으로 기동 실패 | ① › 설정 파일끼리는 Profile, import, 위치, 문서 순서가 정한다(P9)<br>코드에서 고쳐야 하는 것 › Profile은 group으로 묶고 파일 이름에 넣지 않는다(P9b) | `PropLabPlus.kt` `runP9` | `P9.log` |
| P10·P10c | location 콤마 순서, `spring.config.location` 대체, 같은 위치 `.properties`·`.yml`, import 파일 없음 | 뒤 경로가 이긴다. `spring.config.location` 은 classpath `application.yaml` 을 목록에서 뺀다. `.properties` 가 이긴다. `optional:` 없는 import 는 `ConfigDataResourceNotFoundException` | ① › 설정 파일끼리는 Profile, import, 위치, 문서 순서가 정한다 | `PropLabPlus.kt` `runP10` | `P10.log` |
| P11 | 리스트·Map·네 번째 후보·리스트 원소 레거시 표기·`numberOfElements` | `prop_envx_my_target_value`·`PROP_ENVX_ITEMS_0_SERVER_ADDRESS` 모두 4.1.1 에서 붙는다. `PROP_ENVX_my_target_value` 만 null. 원소 수는 `PROP_RELAX_my-target-value`·`myTargetValue`·`MYTARGETVALUE`=3, `PROP_RELAX_my_target_value`·`MY_TARGET_VALUE`=5, canonical name=3 | ② › 환경 변수 이름은 점을 밑줄로 바꾸고 대시는 지우고 대문자로 쓴다(권장 표기 넷, 레거시 리스트 표기)<br>② › 대문자 접두사에 소문자 snake를 붙인 환경 변수는 조용히 null이 된다(원소 수, 소문자 후보) | `PropLabPlus.kt` `runP11`·`runP11Os` | `P11.log`, `P11-os.log` |
| P11N | 같은 환경 변수 표기 여섯을 `-systemEnvironment` 이름의 `SystemEnvironmentPropertySource` 로 넣되 열거형(보통 Map)과 비열거형(`size()` 가 `UnsupportedOperationException`)으로 각각 등록 | 비열거형이면 Boot 가 `SpringConfigurationPropertySource`(역매핑 없음)로 감싸고 `PROP_RELAX_my-target-value`·`PROP_RELAX_myTargetValue` 만 null 로 바뀐다. `PROP_RELAX_MY_TARGET_VALUE`·`PROP_RELAX_MYTARGETVALUE`·`prop_relax_my_target_value` 는 두 경우 모두 붙고, `PROP_RELAX_my_target_value` 는 두 경우 모두 null | ② › 대문자 접두사에 소문자 snake를 붙인 환경 변수는 조용히 null이 된다(두 경로 가르기) | `PropLabPlus.kt` `runP11n` | `P11n.log` |
| P12 | 테스트 소스 우선순위 | `@DynamicPropertySource` > `@TestPropertySource(properties)` > `@SpringBootTest(properties)` > `@TestPropertySource(locations)` > servlet > `-D` > OS env | ① › 테스트 프로퍼티는 운영 소스 위에 얹힌다 | `src/test/kotlin/com/petercoders/blog/prop/PropTestLab.kt`, `src/test/resources/com/petercoders/blog/prop/proptestlab.properties` | `P12.log` |
| P13 | actuator `/env`·`/configprops` 실제 HTTP | 기본 never 는 `******`. `/env` 의 propertySources 에 `configurationProperties` 가 없다. 기동 뒤 `addFirst` 하면 `/env` 는 after, `/configprops` 는 before | 배포한 다음 운영에서 확인하는 것 › /actuator/env는 소스 순서와 승자를 그대로 보여 준다<br>배포한 다음 운영에서 확인하는 것 › env는 입력이고 configprops는 적용된 값이다 | `PropLabExtra.kt` `runP13` | `P13.log` |
| P14·P14b | `@Value` 와 진짜 OS env, 기본값 없는 `@Value` 의 PSPC 유무 | OS env 하나로 `@Value(kebab)`·`@Value(camel)` 둘 다 붙는다(camel 은 `systemEnvironment` 소스 자체 치환). 기본값 없는 `@Value(camel)` + kebab 파일 키는 PSPC 없으면 `${...}` 문자열이 그대로 들어가고, PSPC 있으면 `PlaceholderResolutionException` 으로 기동 실패 | ② › @Value 키는 소문자 kebab-case로 쓴다 | `PropLabPlus.kt` `runP14`·`runP14b` | `P14.log`, `P14b.log` |
| P15 | `@Validated`(P15a), 오타 키(P15b), properties-migrator 와 4.1.1 메타데이터(P15c) | 빈 `@NotBlank` 는 기동 실패. 오타 키는 기본 조용히 null, `ignoreUnknownFields=false` 면 기동 실패. `server.error.include-message` 는 migrator 가 "incompatible target type" ERROR 보고만 하고 이관하지 않는다(대조군 `spring.resources.add-mappings` 는 이관). `@PropertySource` 의 옛 키는 보고·이관 둘 다 없다. `management.endpoints.enabled-by-default` 는 메타데이터에 deprecated(since 3.4.0), replacement `management.endpoints.access.default` | 코드에서 고쳐야 하는 것 › 설정 클래스는 @ConfigurationProperties와 @Validated로 묶는다(P15a)<br>코드에서 고쳐야 하는 것 › 오타와 옛 키는 기동 시점에 걸러 낸다(P15b·P15c)<br>배포한 다음 운영에서 확인하는 것 › /actuator/env는 소스 순서와 승자를 그대로 보여 준다(`enabled-by-default` 메타데이터) | `PropLabExtra.kt` `runP15` | `P15.log` |
| P15d | Kotlin constructor binding 클래스의 `@NotBlank` 를 use-site target 없이 / `@param:` / `@field:` 로 달고 값 없이 기동(컴파일 옵션 `-Xannotation-default-target=param-property`) | 없음 → 필드+생성자 파라미터, 기동 실패. `@param:` → 생성자 파라미터만, `name=null` 로 기동(검증 안 됨). `@field:` → 필드만, 기동 실패 | 코드에서 고쳐야 하는 것 › 설정 클래스는 @ConfigurationProperties와 @Validated로 묶는다 | `PropLabExtra.kt` `runP15d` | `P15d.log` |
| P17 | `@ConfigurationProperties` 의 BindMethod | `data class RelaxProps(var myTargetValue: String? = null)` 는 `VALUE_OBJECT`(constructor binding), 인자 없는 생성자 + `var` 클래스는 `JAVA_BEAN` | ③ 바인딩은 Bean을 만들 때 한 번뿐이다(constructor binding 정의)<br>③ › Environment는 바뀌어도 Bean 필드는 그대로다<br>③ › 재기동 없이 바꾸려면 refresh 경로를 따로 붙인다 | `PropLabPlus.kt` `runP17` | `P17.log` |
| P18 | Kubernetes Service 변수 `REDIS_PRIMARY_PORT=tcp://10.0.0.11:6379` 와 prefix `redis.primary` | `redis.primary.port`(Int) 바인딩 실패로 기동 실패. Origin 은 `System Environment Property "REDIS_PRIMARY_PORT"` | ② › 환경 변수 이름은 점을 밑줄로 바꾸고 대시는 지우고 대문자로 쓴다(Kubernetes Service 변수) | `PropLabPlus.kt` `runP18` | `P18.log`, `P18-k8s7.log` |
| P19 | Boot 4.1.1 + spring-cloud-context 5.0.3(기본) / 5.1.0-M1(`-PcloudContext`)의 `ConfigurationPropertiesRebinder` 에 EnvironmentChangeEvent 한 번 | 5.0.3: `JavaBeanProps` before→after, `RelaxProps`(data class, var + 기본값) before→**null**, `EnvcProps`(val 생성자) before 그대로. 5.1.0-M1: `RelaxProps` 는 before 그대로, 나머지 같음. 모두 같은 인스턴스, rebinder 에러 없음 | ③ › 재기동 없이 바꾸려면 refresh 경로를 따로 붙인다<br>도입(시점 결론), 도입 체크리스트(런타임 변경) | `PropLabExtra.kt` `runP19`(Spring Cloud 클래스는 리플렉션) | `P19.log`, `P19-m1.log` |
| R1 | 1차 문서 원문 대조(Boot external-config, Framework 테스트 문서, actuator env 예시, Kubernetes Service). R1b(2026-10-10): external-config 덮어쓰기 규칙 문장과 목록 항목 수, Kotlin use-site target 규칙. R1c: kotlin-web-site 문서 변경 이력 | Boot 문서의 15항목 목록은 `@TestPropertySource`(14번)를 `@DynamicPropertySource`(13번) 위에 적지만 P12 실측과 Framework 7.0.9 문서는 dynamic 이 이긴다 | ① › 테스트 프로퍼티는 운영 소스 위에 얹힌다(Boot 문서와의 차이)<br>코드에서 고쳐야 하는 것 › 설정 클래스는 @ConfigurationProperties와 @Validated로 묶는다(Kotlin 규칙)<br>검증 메모 | 로그 머리에 첨부한 `r1.py`·`r1b.py`·`r1c.sh` | `R1-docs.log` |
| V | 버전 주장 태그 대조. 2026-10-10 추가분: 환경 변수 두 경로·ConfigData 순서·Spring Cloud 재바인딩 소스, spring-cloud-commons #1733(vc2), 본문 소스 permalink 16개의 행 범위 원문(vc3) | Spring Boot·Cloud 태그 소스, Maven Central, GitHub issue | 본문 ①(ConfigData 순서)·②(두 경로)·③(재바인딩)·코드 절(migrator, Profile 문서 검사)의 태그 소스 permalink 행 범위, 검증 메모 | 로그 머리와 각 절 머리에 첨부한 `vc.sh`·`vc2.sh`·`vc3.sh` | `version-checks.log` |

## 빌드·실행

코드는 `PropLab.kt`(공용 도구·P1~P8·진입점), `PropLabPlus.kt`(P4'·P4b·P9~P11N·P14·P17·P18), `PropLabExtra.kt`(P13·P15·P15d·P19), `src/test/kotlin/com/petercoders/blog/prop/PropTestLab.kt`(P12)와 그 `@TestPropertySource(locations)` 파일 `src/test/resources/com/petercoders/blog/prop/proptestlab.properties` 다. `PropLab.kt` 의 진입점이 `PropLabPlus.kt`·`PropLabExtra.kt` 의 프로브를 부르므로 세 파일은 함께 있어야 컴파일된다. `main(args)` 가 `args[0]` 마커 하나만 돈다. 태스크는 여섯이다.

- `propLab`: main runtimeClasspath. `-PpropJvm='-D...'` 를 주면 실행 JVM 에 진짜 `-D` 로 넘긴다.
- `propLabCwd`: `propLab` 과 같은 클래스패스에 `workingDir=build/proplab-cwd` (P4b 의 `file:./`).
- `propLabActuator`·`propLabExtra`: main runtimeClasspath + `propLabExtra` configuration(actuator·validation·properties-migrator). 이 셋은 main 클래스패스에 넣지 않는다.
- `propLabCloud`: main runtimeClasspath + `propLabCloud` configuration(spring-cloud-context, 기본 5.0.3, `-PcloudContext=5.1.0-M1` 로 바꾼다). Boot BOM 이 관리하지 않아 버전을 직접 적었다. P19 는 Spring Cloud 클래스를 이름 문자열과 리플렉션으로만 써서 main 컴파일 클래스패스에도 넣지 않는다.
- `propTestLab`: `PropTestLab*` 만 도는 전용 Test 태스크. `systemProperty prop.lab.value=fromSystemProperty`, `environment PROP_LAB_VALUE=fromOsEnv` 를 넣는다(공용 test 설정은 그대로).

새로 clone 한 저장소에는 Gradle 의존성 캐시가 없으니 첫 실행은 `--offline` 없이 돌려 의존성을 받는다. 그 뒤로는 `--offline` 을 붙여도 된다(로그의 `# command` 는 캐시가 찬 기기에서 `--offline` 으로 돌린 것이다).

```bash
./gradlew -q propLab --args='P1'        # P2 P3 P5 P6 P7 P8 P9 P10 P11 P11N P14B P17 도 같은 꼴
PROP_LAB_VALUE=fromOsEnv ./gradlew -q propLab -PpropJvm='-Dprop.lab.value=fromSystemProperty' \
  --args='P4 --prop.lab.value=fromCommandLine --spring.profiles.active=dev'
./gradlew -q propLabCwd --args='P4B'
PROP_ENVX_ITEMS_0_SERVER_ADDRESS=osLegacyAddr ./gradlew -q propLab --args='P11OS'
PROP_RELAX_MYTARGETVALUE=fromEnv ./gradlew -q propLab --args='P14'
REDIS_PRIMARY_PORT=tcp://10.0.0.11:6379 ./gradlew -q propLab --args='P18'
./gradlew propTestLab --console=plain
./gradlew -q propLabActuator --args='P13'
./gradlew -q propLabExtra --args='P15'   # P15D 도 같은 태스크
./gradlew -q propLabCloud --args='P19'   # -PcloudContext=5.1.0-M1 → P19-m1.log
```

각 로그 머리의 `# command` 가 실제로 돌린 명령 전체다. 2026-10-08·10-10 재실측은 다른 글의 실험과 동시에 돌았기 때문에 저장소를 `/tmp/gradle-prop` 으로 rsync 한 사본에서 돌렸다(로그의 `user.dir` 이 그 경로다). 공용 `src/main/resources/application.yaml` 은 어떤 프로브도 건드리지 않는다. 실험용 yml 은 프로브가 `java.io.tmpdir` 아래 매번 새 디렉터리에 직접 쓴다.

## `runs/` 로그 색인

| 파일 | 내용 |
|---|---|
| `P1.log` | E1 실행 환경 + P1 |
| `P2.log` · `P3.log` · `P8.log` | E2 재실행(이름 전체, 머리 포함) |
| `P4.log` | P4' 클린 서열, 세 시점 덤프, initializer `addFirst`, P16b |
| `P4-legacy.log` | 옛 P4(인공 소스 둘 섞임). 보존만 한다 |
| `P4b.log` | jar 밖 대 jar 안 |
| `P5.log` · `P6.log` | 2026-09-28 실행분(재실행 안 함) |
| `P7.log` | PSPC 유무 두 조건 |
| `P9.log` · `P10.log` | 프로파일·location |
| `P11.log` · `P11-os.log` | 환경 변수 표기(주입 소스 / 진짜 OS env) |
| `P11n.log` | 환경 변수 소스 열거형 / 비열거형(두 경로 가르기) |
| `P12.log` | 테스트 소스 우선순위 |
| `P13.log` | actuator HTTP. 전체 `/env` 본문은 이 기기의 OS 환경 변수 이름을 담아 남기지 않고 소스 이름만 뽑았다 |
| `P14.log` · `P14b.log` | `@Value` 와 OS env, PSPC |
| `P15.log` | `@Validated` · 오타 키 · migrator |
| `P15d.log` | Kotlin 생성자 프로퍼티의 `@NotBlank` 자리(no-target / `@param:` / `@field:`) |
| `P17.log` | BindMethod |
| `P18.log` · `P18-k8s7.log` | Kubernetes Service 변수(하나 / 문서 예시 7개) |
| `P19.log` · `P19-m1.log` | Spring Cloud EnvironmentChangeEvent 재바인딩(spring-cloud-context 5.0.3 / 5.1.0-M1) |
| `R1-docs.log` | 문서 원문 대조 |
| `version-checks.log` | 버전 주장 태그 대조 |

## 이번에 재지 않은 것

- `@RefreshScope` 와 `/actuator/refresh`(ContextRefresher, Config Server 연동). EnvironmentChangeEvent 재바인딩은 P19 로 쟀다. Boot 4.1.x 기준 Spring Cloud GA 가 없어 Boot 4.0.8 기준 빌드인 spring-cloud-context 5.0.3 과 Boot 4.2.0-M2 기준인 5.1.0-M1 을 Boot 4.1.1 에 직접 붙였다(`version-checks.log`).
- `SPRING_APPLICATION_JSON` 의 자리.
- `EnvironmentPostProcessor` 가 `addFirst` 한 소스의 자리.
- `-Xannotation-default-target` 옵션 없이 컴파일한 빌드. 옛 규칙(생성자 파라미터에만 붙음)의 결과는 P15d 의 `@param:` 로만 흉내 냈다.

git add/commit/push 는 이 저장소 관례상 사용자가 직접 한다.
