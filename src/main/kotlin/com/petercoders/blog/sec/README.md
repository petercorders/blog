# Method Security 실험 코드

velog 글 **"Spring Method Security: @PreAuthorize를 붙이기 전에 확인할 것"** (SPRING-INTERNALS 시리즈 2편, 게시 후 링크로 교체)에서 쓴 실험 전부다.
본문에는 요지 코드와 결정적 출력 몇 줄만 남기고, 돌아가는 전체 코드와 원본 로그를 여기에 둔다.

판정은 로그 문자열이 아니라 **예외 클래스, `sec_log` 행 수, 카운터 값**으로만 한다. 절대 수치(클래스 이름의 해시 접미사, 스레드 이름 번호 등)는 이 기기·이 실행에 종속된다. 이식 가능한 것은 "걸리느냐/안 걸리느냐", "바깥이냐 안쪽이냐", "order 값의 대소 관계" 같은 방향이다.

## 환경

| | |
|---|---|
| CPU | Apple M5 Pro (15코어, arm64), `hw.cachelinesize` 128B |
| OS | macOS Darwin 25.6.0 |
| 실행 JVM | Gradle 툴체인 25 = Microsoft OpenJDK 25.0.4.1 (build 25.0.4.1+1-LTS). `secLab`/`secLabExtra`/`test` 태스크 모두 이 JVM으로 돈다. 새 마커 로그의 `# runtime:` 줄(`java.runtime.version=25.0.4.1+1-LTS java.vendor=Microsoft`)이 실행 JVM을 직접 찍은 값이다 |
| 셸 기본 JVM | `/usr/bin/java` = OpenJDK 26.0.2.1. `s1`~`s6` 로그 머리의 `java -version: 26.0.2.1`은 셸 기본값을 적은 것이고 실행 JVM이 아니다(`s2.log` 4행이 툴체인 25를 적어 두었다) |
| Kotlin | 2.3.21 |
| Gradle | 9.7.1 (wrapper) |
| Spring Boot | 4.1.1 → Spring Framework 7.0.9 / Spring Security 7.1.1 (`s1.log`) |
| DB | H2 인메모리 (`jdbc:h2:mem:seclab*`, 컨텍스트마다 별 DB) |

`build.gradle.kts`에서 이 글과 관계있는 것:

- `spring-boot-starter-security` (main).
- `spring-boot-starter-web`은 3편(예외 처리) 실험용으로 main에 들어 있다. 2편 러너는 `spring.main.web-application-type=none`이고 `SecurityContextHolder`에 `TestingAuthenticationToken`을 직접 넣어 HTTP 없이 인가만 잰다(S15의 (1b)만 `server.port=0` 서블릿 컨텍스트를 한 번 띄운다).
- **`secLabExtra` 구성과 같은 이름의 태스크.** `io.micrometer:context-propagation`(1.2.1, S8g), `org.springframework.security:spring-security-access`(7.1.1, S10), `org.jetbrains.kotlinx:kotlinx-coroutines-core`(1.10.2, S8d)를 이 태스크 classpath에만 붙인다. main runtime classpath에는 넣지 않는다. 5편 A2e가 "context-propagation 없음"을 전제로 재고, 다른 lab도 main runtime classpath를 쓰기 때문이다. 코루틴은 S8d 컴파일용으로 `compileOnly`에도 있다(런타임에는 없다). 버전은 Boot BOM이 정한다. 첫 실행은 의존을 받아야 해서 `--offline` 없이 돈다.
- `testImplementation("org.springframework.security:spring-security-test")` (S13).
- `sourceSets.main`에서 `sec/s9src/**`를 뺀다. S9용 Java 소스는 Boot 플러그인이 `-parameters`를 거는 Gradle 컴파일을 거치면 안 되기 때문이다.

`SecLab.kt`의 `boot()`와 `SecLabSupport.kt`의 `bootSec()`에는 `spring.main.allow-circular-references=true`가 들어 있다. `SecureProbe`가 자기 자신을 필드로 주입받는데(`self`), Boot 2.6부터 순환 참조가 기본 금지이고 4.1.1도 같다(`ApplicationProperties.allowCircularReferences` 기본 false).

## 파일

| 파일 | 무엇을 보나 | 본문 절 |
|---|---|---|
| [`SecLab.kt`](SecLab.kt) | 러너 `main()`. 마커(S1~S16) 하나가 `when` 분기 하나다. S1·S5 실행 함수도 여기 있다 | 전부 |
| [`SecLabSupport.kt`](SecLabSupport.kt) | 프로필·DB 이름별 컨텍스트 부팅(`bootSec`), 실행 JVM 출력, 체인 출력(`printChain` = 진단 레시피) | 전부 |
| [`SecProbes.kt`](SecProbes.kt) | `EvalCounter`, `FilterProbe`(S5), `OrderProbe.withdraw`(S4·S4b·S4c·S4e·S15) | ② @PostAuthorize 거부보다 commit이 먼저 끝난다 · ③ @PostFilter는 쿼리가 끝난 뒤 원소마다 돈다 |
| [`SecProbesS1b.kt`](SecProbesS1b.kt) | `AllAdvicesProbe`(다섯 annotation + `@Transactional`)와 Bean별 실제 Advisor 체인 | @EnableMethodSecurity 한 줄이 실제로 바꾸는 것 |
| [`SecProbesS2.kt`](SecProbesS2.kt) | `SecureProbe` — 1편 `BypassProbe`와 같은 여덟 경로 | ① 우회 경로에서는 권한 검사 없이 본문이 실행된다 · Kotlin에서는 final 클래스가 기동을 막고 final 메서드가 검사를 건너뛴다(`final` 행) |
| [`SecProbesS3.kt`](SecProbesS3.kt) | `TxOrderProbe` — `@PreAuthorize` SpEL 안에서 `isActualTransactionActive()` | 순서를 정하는 것은 order 숫자 하나다 |
| [`SecProbesS3b.kt`](SecProbesS3b.kt) | S3b(txouter에서 Pre 평가 시점), S3c(`@ownerCheck.check(#id)` + `@Transactional(readOnly = true)`) | 고치는 방법과 각각의 대가 |
| [`SecProbesS4.kt`](SecProbesS4.kt) | S4(기본 order 거부 후 행 잔존), S4b(Transaction order=0) | ② @PostAuthorize 거부보다 commit이 먼저 끝난다 · 순서를 정하는 것은 order 숫자 하나다 |
| [`SecProbesS4x.kt`](SecProbesS4x.kt) | S4c(`TxMidConfig` order=300), S4e(`OuterService`가 거부 예외를 삼킴), S4f(`OwnedReader`/`WriterService` 읽기-인가-후-쓰기) | 거부 예외를 잡아 삼키는 코드는 order마다 다르게 깨진다 · 고치는 방법과 각각의 대가 |
| [`SecProbesS5d.kt`](SecProbesS5d.kt) | `DocAcl`·`DocListProbe` — S5d(`@PostFilter` 식 안의 `@docAcl.canRead(...)` 조회 횟수를 H2 `QUERY_STATISTICS`로 셈, where 절 대조군) | 목록은 데이터 계층에서 거른다 |
| [`SecConfig.kt`](SecConfig.kt) | `TxOuterConfig` — `@EnableTransactionManagement(order = 0)`, 프로필 `txouter`(S3b·S4b·S4e·S7) | 순서를 정하는 것은 order 숫자 하나다 · 거부 예외를 잡아 삼키는 코드는 order마다 다르게 깨진다 · 고치는 방법과 각각의 대가 · 예외 대신 값을 돌려주면 rollback도 사라진다 |
| [`SecProbesS5x.kt`](SecProbesS5x.kt) | `ListIdentityProbe` — S5b(`listOf()`), S5c(가변 리스트 공유 참조) | 돌려받는 리스트가 원본인지 복사본인지는 리스트 종류가 정한다 |
| [`SecProbesS6.kt`](SecProbesS6.kt) | `DenialProbe` — 거부 예외 타입, `@HandleAuthorizationDenied` 대체값 | 거부 사유는 규칙 문자열로만 온다 |
| [`SecProbesS7.kt`](SecProbesS7.kt) | `FallbackWriteProbe` + `AccountMaskingHandler` — txouter에서도 대체값이면 commit | 예외 대신 값을 돌려주면 rollback도 사라진다 |
| [`SecProbesS8.kt`](SecProbesS8.kt) | `AsyncCaller`·`AsyncSecured`(프로필 `async`), `DelegatingExecutorConfig`(프로필 `asyncdelegating`), S8c(Virtual Thread Executor), S8e inheritable, S8g(context-propagation) | 비어 있으면 AccessDeniedException이 아닌 예외로 막힌다 · @Async와 같은 메서드에 걸면 검사는 Executor 스레드에서 돈다 · 전파는 Executor마다 따로 건다 · Virtual Thread와 Coroutine도 같은 규칙을 따른다 |
| [`SecProbesS8d.kt`](SecProbesS8d.kt) | Coroutine 디스패치 경계(`secLabExtra` 전용 파일) | Virtual Thread와 Coroutine도 같은 규칙을 따른다 |
| [`SecProbesS9.kt`](SecProbesS9.kt) | S9b(`ParamNameProbe` 이름 불일치), S9(`-parameters` 없는 Java 클래스를 URLClassLoader로 등록) | #id 파라미터 이름은 컴파일러가 확인하지 않는다 |
| [`s9src/.../S9Target.java`](s9src/com/petercoders/blog/secx/s9/S9Target.java) | S9 전용 Java 소스(Gradle 컴파일 제외, javac 명령은 `runs/s9.log` 머리) | 같은 절 |
| [`SecProbesS12to16.kt`](SecProbesS12to16.kt) | S12(`finalbean`), S14(`retry`), S15(`events`), S16(`s16`) — 프로필로 격리 | Kotlin에서는 final 클래스가 기동을 막고 final 메서드가 검사를 건너뛴다 · 고치는 방법과 각각의 대가 · 거부는 이벤트 publisher Bean을 등록해야 기록된다 · annotation은 메서드에 붙이고 문자열은 한 곳에 둔다 |
| [`SecProbesSnippets.kt`](SecProbesSnippets.kt) | 본문 요지 코드 컴파일·실행 확인: S8s(`runAsSystem`), S16b(meta-annotation `@AdminOnly`) | 사용자가 없는 호출은 신원을 정해서 부른다 · annotation은 메서드에 붙이고 문자열은 한 곳에 둔다 |
| [`../secx/global/GlobalLab.kt`](../secx/global/GlobalLab.kt) | S10 — `@EnableGlobalMethodSecurity`만 붙인 별도 앱(sec 패키지를 스캔하지 않음, `@Profile("s10")`) | @EnableGlobalMethodSecurity에서 옮기면 순서가 바뀐다 |
| [`../secx/offset/`](../secx/offset/) | S4d — `@EnableMethodSecurity(offset = 1000 / Int.MAX_VALUE)` 별도 앱 둘(`@Profile("s4d")`) | 고치는 방법과 각각의 대가 |
| [`src/test/.../sec/SecS13Test.kt`](../../../../../../test/kotlin/com/petercoders/blog/sec/SecS13Test.kt) | S13 — `@WithMockUser(roles = ["USER"])` 무권한 테스트. 한쪽은 일부러 실패하는 시연이라 `SEC_S13=1`일 때만 돈다 | 테스트는 무권한 사용자로 경로마다 부른다 |

## 실행

```bash
cd ~/Desktop/development/blog
./gradlew --offline -q secLab --args='S1'     # S1 S1b S2 S3 S3b S3c S4 S4b S4c S4d S4e S4f S5 S5b S5c S5d S6 S7 S8 S8c S8e S8s S9b S10 S12 S14 S15 S16 S16b
./gradlew -q secLabExtra --args='S8g'         # S8g S8d S10(2) — context-propagation·spring-security-access·coroutines 가 붙은 classpath
./gradlew --offline -q secLab --args='S9 build/s9classes'   # 먼저 S9Target.java 를 -parameters 없이 javac (명령은 runs/s9.log 머리)
SEC_S13=1 ./gradlew --offline test --tests 'com.petercoders.blog.sec.SecS13Test'
```

실제 로그는 다른 글의 실험과 겹치지 않도록 저장소를 `/tmp/gradle-sec/`로 rsync한 사본에서 돌렸다(로그 머리의 `# command:` 줄).

## 마커 → 본문 절 → 로그

| 마커 | 본문에서 다루는 것 | 로그 | 본문 절 |
|---|---|---|---|
| `S1` | Advisor Bean 전체 목록·클래스·order(다섯 + Transaction), `internalAutoProxyCreator` 실제 클래스 | `s1.log` | @EnableMethodSecurity 한 줄이 실제로 바꾸는 것 |
| `S1b` | Bean별 실제 체인(`Advised.advisors`). `AllAdvicesProbe`는 100·200·450·500·600·MAX 여섯, 같은 order 중복 없음 | `s1b.log` | @EnableMethodSecurity 한 줄이 실제로 바꾸는 것 |
| `S2` | 여덟 경로 × admin/user 판정 | `s2.log` | ① 우회 경로에서는 권한 검사 없이 본문이 실행된다 · Kotlin에서는 final 클래스가 기동을 막고 final 메서드가 검사를 건너뛴다(`final` 행) |
| `S3` | order 재확인 + `@PreAuthorize` 평가 시점 Transaction 없음(false) | `s3.log` | 순서를 정하는 것은 order 숫자 하나다 |
| `S3b` | Transaction order=0이면 `@PreAuthorize` 평가 시점에 Transaction이 열려 있음(true) | `s3b.log` | 고치는 방법과 각각의 대가 |
| `S3c` | `@ownerCheck.check(#id)`의 check에 `@Transactional(readOnly = true)` → check 안 true/readOnly true, 프로브 본문 false. 대조군(annotation 없음) false | `s3c.log` | 고치는 방법과 각각의 대가 |
| `S4` | 기본 order에서 `@PostAuthorize` 거부 후에도 행 1 | `s4.log` | ② @PostAuthorize 거부보다 commit이 먼저 끝난다 |
| `S4b` | Transaction order=0이면 같은 거부가 rollback, 행 0 | `s4b.log` | 순서를 정하는 것은 order 숫자 하나다 |
| `S4c` | Transaction order=300: Pre 평가 시점 false, Post 거부 행 0 | `s4c.log` | 고치는 방법과 각각의 대가 |
| `S4d` | offset=1000 → prePost 넷 1100~1600, 450 그대로, 거부 행 1. offset=MAX → prePost 넷 음수, 450 그대로, 거부 행 1 | `s4d.log` | 고치는 방법과 각각의 대가 |
| `S4e` | 바깥 `@Transactional`이 거부 예외를 삼키면: 기본 order는 예외 없이 행 1, order=0은 `UnexpectedRollbackException`·행 0 | `s4e.log` | 거부 예외를 잡아 삼키는 코드는 order마다 다르게 깨진다 |
| `S4f` | 읽기(`@PostAuthorize`)에서 인가하고 통과하면 쓰기: bob 소유 거부·행 0, alice 소유 행 1 | `s4f.log` | 고치는 방법과 각각의 대가 |
| `S5` | `@PreAuthorize`(호출당 1회) vs `@PostFilter`(원소 수만큼) SpEL 평가 횟수 | `s5.log` | ③ @PostFilter는 쿼리가 끝난 뒤 원소마다 돈다(기록 절에서도 인용) |
| `S5b` | 7.1.1에서 `listOf()` 반환: 예외 없음, 새 `ArrayList`(크기 2), 입력은 크기 3 그대로 | `s5b.log` | 돌려받는 리스트가 원본인지 복사본인지는 리스트 종류가 정한다 |
| `S5c` | 가변 리스트 반환: 크기 5, `returned === shared`, 공유 크기도 5, 평가 10회 | `s5c.log` | 돌려받는 리스트가 원본인지 복사본인지는 리스트 종류가 정한다 |
| `S5d` | `@PostFilter` 식 안에서 `@bean`으로 JdbcTemplate 조회: 문서 0·3·10개에 권한 쿼리 0·3·10회 + 목록 쿼리 1회(H2 `QUERY_STATISTICS` 실행 횟수). 같은 결과를 where 절로 읽으면 늘 1회 | `s5d.log` | 목록은 데이터 계층에서 거른다 |
| `S6` | `AuthorizationDeniedException`이 `AccessDeniedException`으로 잡힘, `getAuthorizationResult()`는 규칙 문자열, 대체값 반환 | `s6.log` | 거부 사유는 규칙 문자열로만 온다 |
| `S7` | txouter에서도 `@HandleAuthorizationDenied` 대체값이면 거부된 쓰기가 남음(행 1) | `s7.log` | 예외 대신 값을 돌려주면 rollback도 사라진다 |
| `S8` | (a) 기본 Executor: `ExecutionException` ← `AuthenticationCredentialsNotFoundException`(AccessDeniedException 아님) (b) `DelegatingSecurityContextAsyncTaskExecutor`: 통과 (f) `@Async`+`@PreAuthorize` 한 메서드: 체인 첫 항목 `AsyncAnnotationAdvisor`, Executor 스레드에서 막힘, void는 `AsyncUncaughtExceptionHandler`로 | `s8.log` | (a) 비어 있으면 AccessDeniedException이 아닌 예외로 막힌다 · (b) 전파는 Executor마다 따로 건다 · (f) @Async와 같은 메서드에 걸면 검사는 Executor 스레드에서 돈다 |
| `S8c` | Virtual Thread Executor(`isVirtual=true`)에서 S8a·S8f와 같은 결과 | `s8.log`(이어 씀) | Virtual Thread와 Coroutine도 같은 규칙을 따른다 |
| `S8e` | `MODE_INHERITABLETHREADLOCAL` + core=1 풀: bob 요청이 alice context로 돔 | `s8.log`(이어 씀) | 전파는 Executor마다 따로 건다 |
| `S8g` | context-propagation + `spring.task.execution.propagate-context=true`: 플랫폼·가상 스레드 모두 통과. 라이브러리만 있고 프로퍼티가 없으면 S8a와 같음 | `s8.log`(이어 씀) | 전파는 Executor마다 따로 건다(표의 Virtual Thread 행 포함) |
| `S8d` | `withContext(Dispatchers.Default)` 안에서 `AuthenticationCredentialsNotFoundException`, 같은 스레드는 통과 | `s8.log`(이어 씀) | Virtual Thread와 Coroutine도 같은 규칙을 따른다 |
| `S8s` | 사용자 없는 스레드: 그냥 부르면 `AuthenticationCredentialsNotFoundException`·행 0, `runAsSystem`이면 행 1, 끝난 뒤 context 비어 있음 | `s8.log`(이어 씀) | 사용자가 없는 호출은 신원을 정해서 부른다 |
| `S9` | `-parameters` 없는 Java 클래스: `byId("alice")` 거부, `@P("id")`는 통과 | `s9.log` | #id 파라미터 이름은 컴파일러가 확인하지 않는다 |
| `S9b` | Kotlin 이름 불일치: `byOrderId("alice")` 거부, `#id != 'blocked'`는 `"blocked"`도 통과(행 1) | `s9b.log` | #id 파라미터 이름은 컴파일러가 확인하지 않는다 |
| `S10` | (1) access 모듈 없으면 기동 실패(`BeanDefinitionStoreException` ← `IllegalStateException`) (2) 있으면 Security·Transaction Advisor 모두 `Integer.MAX_VALUE`, 이 실행에서는 거부 행 1 | `s10.log` | @EnableGlobalMethodSecurity에서 옮기면 순서가 바뀐다 |
| `S12` | `@Bean`으로 등록한 final 클래스: 기동 실패(`BeanCreationException` ← `AopConfigException` ← `IllegalArgumentException: Cannot subclass final class`) | `s12.log` | Kotlin에서는 final 클래스가 기동을 막고 final 메서드가 검사를 건너뛴다 |
| `S13` | `@WithMockUser(roles=["USER"])`: secured 테스트 통과, selfInvoke 테스트 실패(예외가 안 남) | `s13.log` | 테스트는 무권한 사용자로 경로마다 부른다 |
| `S14` | `@Retryable(maxRetries = 2)` + `@Transactional` + `@PostAuthorize` 거부: retry Advisor가 체인 맨 앞, 본문 3회, 행 3. APC는 `InfrastructureAdvisorAutoProxyCreator` 그대로 | `s14.log` | 고치는 방법과 각각의 대가 |
| `S15` | publisher Bean 기본 없음(웹 none·서블릿 모두). 등록하면 Pre 거부 1·Post 거부 1·`@PostFilter` 제거 0·통과 0. 통과까지 발행하면 `AuthorizationDeniedEvent(isGranted=true)` | `s15.log` | 거부는 이벤트 publisher Bean을 등록해야 기록된다 |
| `S16` | 두 인터페이스의 서로 다른 `@PreAuthorize`: 기동은 되고 첫 호출에서 `AnnotationConfigurationException` | `s16.log` | annotation은 메서드에 붙이고 문자열은 한 곳에 둔다 |
| `S16b` | meta-annotation `@AdminOnly`: USER 거부·행 0, ADMIN 통과·행 1 | `s16.log`(이어 씀) | annotation은 메서드에 붙이고 문자열은 한 곳에 둔다 |

6.3 이하의 `@PostFilter` + 읽기 전용 리스트 동작(`clear()`의 `UnsupportedOperationException`)은 Boot 4 의존과 맞지 않아 실행하지 않았다. `DefaultMethodSecurityExpressionHandler`의 6.3.0/6.4.0 소스 태그 대조로만 쓴다.

## 커밋

이 폴더, `../secx/`, `src/test/.../sec/`, `build.gradle.kts` 변경은 커밋되지 않은 상태로 둔다. `git add`/`commit`/`push`는 사용자가 직접 한다.
