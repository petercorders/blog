# @Transactional 실험 코드

velog 글 **"Spring @Transactional: 붙이기 전에 확인할 것"** (SPRING-INTERNALS 5부작 1편, 게시 후 링크로 교체)에서 쓴 실험 전부다.
본문에는 결과 한 줄과 표·요지 코드만 남기고, 돌아가는 전체 코드와 원본 로그를 여기에 둔다.

절대 수치(스레드 이름, 해시, 대기 ms 등)는 이 기기·이 실행에 종속된다. 이식 가능한 것은 **분기 구조와 행 수**다. 다른 머신에서 돌려도 "CGLIB이냐 JDK냐", "Transaction이 열렸나", "몇 행이 남았나", "어느 DB로 갔나"는 같게 나와야 한다.

## 환경

| | |
|---|---|
| CPU | Apple M5 Pro (15코어, arm64) |
| 캐시 라인 | 128B (`hw.cachelinesize`) |
| OS | macOS Darwin 25.6.0 |
| JDK (Gradle 툴체인, 실제 실행) | Microsoft OpenJDK 25.0.4.1+1-LTS (`java.toolchain.languageVersion=25`) |
| JDK (셸 기본, 실행에 안 씀) | OpenJDK 26.0.2.1 (2026-08-18) |
| Kotlin | 2.3.21 (Gradle, `[e0]`이 런타임 stdlib 버전을 직접 찍는다). e3 의 플러그인 OFF 쪽도 kotlinc-jvm 2.3.21(kotlin-compiler-embeddable)로 컴파일했다 |
| Spring Boot | 4.1.1 → Spring Framework 7.0.9 |
| HikariCP / H2 | 7.0.2 / 2.4.240 (H2 인메모리) |

버전은 `[e0]` 구간이 실행 중인 JVM 에서 직접 찍는다. `[e0]`은 `txLab` 러너가 찍는 로그(e1·e2·e4~e7·e10·e12) 머리에 들어 있다. 러너가 따로인 e3·e8·e9·e11 로그에는 `[e0]`이 없고, 머리의 `# java -version`(e3은 `# kotlinc -version`까지) 줄이 같은 툴체인 JDK를 적는다. 대신 e8은 스택 프레임의 `spring-*-7.0.9.jar`, e9는 `aspectjweaver = 1.9.25.1`, e11은 `kotlinx-coroutines-core-jvm-1.10.2.jar`를 출력에 찍는다. 로그 머리의 `java -version`은 툴체인 JDK(25.0.4.1) 기준이다.

`TxLab.kt`의 `boot()`에는 `spring.main.allow-circular-references=true`가 들어 있다. `BypassProbe`·`NestedProbe`·`PoolProbe`가 자기 자신을 필드로 주입받는데(`self`), Spring Boot 2.6부터 순환 참조가 기본 금지라 이 옵션 없이는 컨텍스트가 뜨지 않는다. 그래서 이 빈들은 `@Profile("!circ-off")`로 묶여 있고, 순환 참조를 끈 `[e2d]`·`[e2g]` 컨텍스트에서는 빠진다.

## 파일

'본문 절'은 원고의 지금 `##`/`###` 제목 그대로다. `##` › `###` 순서로 적었다. `##` 제목 뒤의 `(본문)`은 그 절의 첫 `###` 앞 부분이다. `##`를 번호(①~⑥)만 적은 칸은 이 표나 아래 대응표의 다른 칸에 그 절의 전체 제목이 있다.

| 파일 | 무엇을 보나 | 본문 절 |
|---|---|---|
| [`Probes.kt`](Probes.kt) | `OrderService`/`PlainService`(Proxy 종류), `BypassProbe`(우회 경로·`final` NPE), `NestedProbe`(전파·NESTED), `RollbackProbe`(롤백 규칙·checked 예외) | @Transactional 한 줄이 실제로 바꾸는 것(`OrderService`·`PlainService`의 생성기)<br>① 같은 객체 안의 호출은 Proxy를 지나지 않는다(본문) › 인터페이스가 있어도 Proxy는 CGLIB다 · protected는 6.0부터 CGLIB에서만 걸린다 · Kotlin은 명시한 final 하나가 NPE가 된다<br>② 롤백은 RuntimeException에만 걸리고, Kotlin 도메인 예외는 커밋된다(본문) › Kotlin에서 checked 예외가 자동 롤백된다는 조언은 5.2.0~5.2.14·5.3.0~5.3.2의 동작이다 · rollbackOn = ALL_EXCEPTIONS는 기준선 자체를 옮긴다<br>③ 안쪽이 던진 RuntimeException은 바깥이 삼켜도 전체를 롤백시킨다(본문) › rollback-only 표시는 rollbackOn과 참여 실패 플래그가 함께 정하고, 플래그를 끄면 반쯤 쓴 행까지 커밋된다 · 부분 롤백이 필요하면 NESTED이고 JDBC에서만 된다<br>④ REQUIRES_NEW는 커넥션을 하나 더 잡는다 › Proxy를 지나야 새 Transaction이 된다<br>붙인 다음 운영에서 보는 것 › Transaction 로그는 두 logger에 나뉘어 있다 · TransactionExecutionListener로 begin과 rollback을 센다(`BypassProbe`·`NestedProbe`를 부른다) |
| [`MoreProbes.kt`](MoreProbes.kt) | 재편 때 더한 빈: `@Proxyable`, self 주입 세 갈래, exposeProxy, `@PostConstruct` 변형, 중복 키 upsert, 참여 실패 플래그, `PoolProbe`, Thread 넘기기, `CheckedPort`, `FinalOverrideProbe`, `TransactionExecutionListener`, readOnly 라우팅 | @Transactional 한 줄이 실제로 바꾸는 것(`@PostConstruct` 변형)<br>① › 인터페이스가 있어도 Proxy는 CGLIB다(`@Proxyable`, `JdkInjectProbe`)<br>② › Kotlin에서 checked 예외가 자동 롤백된다는 조언은 5.2.0~5.2.14·5.3.0~5.3.2의 동작이다(`CheckedPort`)<br>③ 안쪽이 던진 RuntimeException은 바깥이 삼켜도 전체를 롤백시킨다(본문, upsert) › rollback-only 표시는 rollbackOn과 참여 실패 플래그가 함께 정하고, 플래그를 끄면 반쯤 쓴 행까지 커밋된다(`noglobal`)<br>④ › 풀은 Tn × (Cm − 1) + 1 이상이어야 교착하지 않는다(`PoolProbe`)<br>⑤ Transaction은 Thread를 넘어가지 않는다 › 다른 Thread로 넘긴 쓰기는 바깥 롤백과 따로 남는다<br>⑥ › DataSource 라우팅은 커넥션을 얻기 전에 정해져야 한다<br>붙인 다음 운영에서 보는 것 › 부팅 로그의 CGLIB 경고는 root 레벨에 따라 사라진다(`FinalOverrideProbe`) · TransactionExecutionListener로 begin과 rollback을 센다<br>코드에서 고쳐야 하는 것 › 같은 클래스 안 호출은 Bean을 나눠서 끊는다(self 주입·exposeProxy) · 규칙은 정적 검사로, 우회는 @Transactional 없는 테스트로 잡는다 |
| [`TxLab.kt`](TxLab.kt) | 위 빈들을 부팅해 `[e0]`·`[e1]`·`[e2]`·`[e4]`~`[e7]`·`[e10]`·`[e12]` 구간을 찍는 러너(`main()`) | 아래 대응표의 e1·e2·e4~e7·e10·e12 절 전부 |
| [`TxLogLab.kt`](TxLogLab.kt) | e8: Transaction 로그 logger·레벨과 부팅 CGLIB 경고(모드마다 새 JVM) | 붙인 다음 운영에서 보는 것 › 부팅 로그의 CGLIB 경고는 root 레벨에 따라 사라진다 · Transaction 로그는 두 logger에 나뉘어 있다 |
| [`../txorder/TxOrderLab.kt`](../txorder/TxOrderLab.kt) | e9: `@Transactional`과 커스텀 `@Aspect`(락 대역)의 Advisor 순서 | ⑥ 같은 메서드의 다른 Advice와의 순서는 order가 정한다 › order가 작을수록 바깥이고, 지정하지 않으면 순서가 정해지지 않는다<br>코드에서 고쳐야 하는 것 › 락·재시도·라우팅 Aspect에는 @Order를 명시한다(요지 코드, 변형 b) |
| [`../txco/TxCoLab.kt`](../txco/TxCoLab.kt) | e11: JDBC TransactionManager + `suspend fun` `@Transactional` | ⑤ › Coroutine에서는 Thread를 바꾸기 전에 Transaction 경계를 닫는다 |
| [`AllOpenProbe.kt`](AllOpenProbe.kt) | all-open이 여는 것과 안 여는 것만 담은 최소 클래스(빈 아님) | ① › Kotlin은 명시한 final 하나가 NPE가 된다 |
| [`PkgPrivateProbe.java`](../../../../../java/com/petercoders/blog/tx/PkgPrivateProbe.java), [`PkgPrivateCaller.java`](../../../../../java/com/petercoders/blog/tx/PkgPrivateCaller.java) (`src/main/java/com/petercoders/blog/tx/`) | `[e2f]` package-private `@Transactional`(Kotlin에는 package-private이 없어 Java로) | ① › protected는 6.0부터 CGLIB에서만 걸린다 |

## 실행

저장소에서 직접 돌리지 않고 사본에서 돌렸다(다른 글의 실험과 동시에 돌기 때문).

```bash
rsync -a --delete --exclude build --exclude .gradle ~/Desktop/development/blog/ /tmp/gradle-tx/ && cd /tmp/gradle-tx
./gradlew --offline -q txLab                                   # e1·e2·e4~e7·e10·e12 (+ e0 머리)
for m in TRACE INFO WARN; do ./gradlew --offline -q txLogLab --args=$m; done   # e8 (모드마다 새 JVM)
./gradlew --offline -q txOrderLab                              # e9 (spring-boot-starter-aspectj 는 이 태스크 classpath 에만)
./gradlew --offline -q txCoLab && ./gradlew --offline -q txCoLabReactor          # e11 (coroutines 는 이 태스크 classpath 에만)
```

`build.gradle.kts`에 이 글을 위해 더한 것: `txLab`·`txLogLab`·`txOrderLab`·`txCoLab`·`txCoLabReactor` 태스크, 전용 configuration `txOrder`(spring-boot-starter-aspectj)·`txCo`(kotlinx-coroutines-core)·`txCoReactor`(kotlinx-coroutines-reactor), 컴파일용 `compileOnly("org.aspectj:aspectjweaver")`. aspectj·coroutines를 main runtimeClasspath에 넣지 않은 이유: 넣으면 다른 랩(sec·exc·async·prop)의 자동 프록시 생성기와 Bean 구성이 바뀐다. spring-boot-starter-aspectj 4.1.1·aspectjweaver 1.9.25.1·kotlinx-coroutines-reactor 1.10.2 jar는 처음 한 번 온라인으로 받아 Gradle 캐시에 넣은 뒤 `--offline`으로 돌렸다.

## 실험 id ↔ 로그 ↔ 구간 마커

로그 파일 하나 = 정수 id 하나(`runs/eN.log`). 그 안의 구간 마커 = `[eN]`/`[eNb]`/`[eNc]`… 본문·그림·README는 이 이름만 쓴다. `txlab.log`는 `txLab` 전체 원본이고, e1·e2·e4~e7·e10·e12의 `eN.log`는 거기서 `[e0]`과 `[eN*]` 구간을 awk로 잘라 낸 것이다(추출 명령은 각 로그 머리에 있다). e3(kotlinc·`javap`), e8(`txLogLab`), e9(`txOrderLab`), e11(`txCoLab`·`txCoLabReactor`)은 따로 도는 러너의 출력 그대로이고, 실행 명령은 각 로그 머리의 `# command:` 줄에 있다.

| id | 로그 | 구간 | 결과 한 줄 | 본문 절 |
|---|---|---|---|---|
| e0 | `txLab` 러너 로그(e1·e2·e4~e7·e10·e12) 머리 | `[e0]` | JDK 25.0.4.1+1-LTS(Microsoft), Spring 7.0.9, Boot 4.1.1, Kotlin 2.3.21, HikariCP 7.0.2, H2 2.4.240 | 도입(실험 환경 문단) |
| e1 | `e1.log` | `[e1]` `[e1b]` `[e1c]` | 기본값은 인터페이스 Bean도 CGLIB, 생성기는 `InfrastructureAdvisorAutoProxyCreator`. `false`면 인터페이스 Bean만 JDK Proxy가 되고 `getBean(OrderService)`는 `NoSuchBeanDefinitionException`, 구현 클래스 생성자 주입은 `BeanNotOfRequiredTypeException`으로 부팅 실패. `@Proxyable(TARGET_CLASS)` Bean은 `false`에서도 CGLIB | @Transactional 한 줄이 실제로 바꾸는 것(`[e1]` 생성기 클래스)<br>① 같은 객체 안의 호출은 Proxy를 지나지 않는다 › 인터페이스가 있어도 Proxy는 CGLIB다(`[e1]`·`[e1b]`·`[e1c]`) · Kotlin은 명시한 final 하나가 NPE가 된다(e1.log 첫머리의 CglibAopProxy DEBUG 줄) |
| e2 | `e2.log` | `[e2]` `[e2c]` `[e2f]` `[e2d]` `[e2g]` `[e2e]` | 여덟 경로 중 열린 것은 외부 호출·self 필드·protected 셋. package-private(Java)도 열린다. 순환 참조 금지에서 self 필드 직접 주입은 `BeanCurrentlyInCreationException`, `@Lazy` self·`ObjectProvider`는 부팅되고 열린다. `@PostConstruct`에서 early Proxy(self)로 부르면 열리고, 순환 참조 금지 + `ObjectProvider.getObject()`는 `BeanCurrentlyInCreationException`. exposeProxy + `AopContext.currentProxy()`는 열린다 | @Transactional 한 줄이 실제로 바꾸는 것(`[e2g]`)<br>① 같은 객체 안의 호출은 Proxy를 지나지 않는다(본문, `[e2]` 여덟 경로) › protected는 6.0부터 CGLIB에서만 걸린다(`[e2c]`·`[e2f]`)<br>코드에서 고쳐야 하는 것 › 같은 클래스 안 호출은 Bean을 나눠서 끊는다(`[e2]`·`[e2d]`·`[e2e]`) |
| e3 | `e3.log`, `allopen-javap.txt` | — | 플러그인은 기본 modality만 open, 명시한 `final`과 `@Transactional` 없는 클래스는 그대로(kotlinc-jvm 2.3.21 OFF/ON, Gradle ON 세 쪽 모두 같은 결과) | ① › Kotlin은 명시한 final 하나가 NPE가 된다 |
| e4 | `e4.log` | `[e4]` | `final` 메서드가 Proxy 인스턴스의 빈 필드 `jdbc`를 읽어 NPE | ① › Kotlin은 명시한 final 하나가 NPE가 된다 |
| e5 | `e5.log`, `e5c-repeat.log` | `[e5]` `[e5b]` `[e5c]` (`[e5c-repeat]` run 1~7) | REQUIRES_NEW를 this로 부르면 2행, self로 부르면 1행. REQUIRED 안쪽 RuntimeException은 catch해도 `UnexpectedRollbackException`·0행, 안쪽이 `DomainFailure : Exception`이면 표시 없이 2행 커밋, NESTED는 바깥 1행만 커밋. 중복 키(`DuplicateKeyException`)를 catch해 update하는 upsert도 전체 롤백. 플래그를 끄면 안쪽 행까지 2행 커밋. 안쪽 본문 active: REQUIRES_NEW 2, NESTED 1. pool=1이면 1초 뒤 `CannotCreateTransactionException`, pool=2·Thread 2는 실행마다 갈린다. `txlab.log` 실행은 한쪽이 1초 대기 뒤 실패하고 그 커넥션이 풀려 다른 쪽이 성공(2행)했지만, 푸시 준비 때 같은 코드를 7번 더 돌린 `e5c-repeat.log`에서는 둘 다 실패(0행) 5번·한쪽만 실패 2번이었다. pool=3이면 8번 모두 둘 다 바로 성공 | ③ 안쪽이 던진 RuntimeException은 바깥이 삼켜도 전체를 롤백시킨다(본문, `[e5]`·`[e5b]` upsert) › rollback-only 표시는 rollbackOn과 참여 실패 플래그가 함께 정하고, 플래그를 끄면 반쯤 쓴 행까지 커밋된다(`[e5b]`) · 부분 롤백이 필요하면 NESTED이고 JDBC에서만 된다(`[e5b]`·`[e5c]`)<br>④ REQUIRES_NEW는 커넥션을 하나 더 잡는다 › Proxy를 지나야 새 Transaction이 된다(`[e5]`) · 풀은 Tn × (Cm − 1) + 1 이상이어야 교착하지 않는다(`[e5c]`)<br>붙인 다음 운영에서 보는 것 › 커넥션 풀이 먼저 신호를 보낸다(`[e5c]`) |
| e6 | `e6.log` | `[e6]` `[e6b]` `[e6c]` `[e6d]` | 다른 Thread에는 묶인 커넥션이 없다. 바깥이 롤백돼도 @Async·supplyAsync·new Thread 행은 남고, parallelStream은 호출 Thread(main)가 처리한 원소만 사라진다(이 실행은 16개 중 3개를 main이 처리해 13개가 남았다. main이 맡는 원소 수는 실행마다 다르다). 바깥이 잡은 행을 작업 Thread가 갱신하면 1초 뒤 `QueryTimeoutException`. `[e6b]`: 같은 키로 `bindResource`를 두 번 하면 `IllegalStateException: Already value [first] for key [probe-key] bound to thread` | ⑤ Transaction은 Thread를 넘어가지 않는다(본문, `[e6]`) › 다른 Thread로 넘긴 쓰기는 바깥 롤백과 따로 남는다(`[e6c]`·`[e6d]`). `[e6b]`는 본문에 인용하지 않았다 |
| e7 | `e7.log` | `[e7]` `[e7c]` `[e7d]` `[e7b]` `[e7e]` | 기본은 RuntimeException만 롤백. 거리: Exception→ISE 2, 자기 자신 0, 미일치 -1. 문자열 규칙 "Failure"는 `DataAccessResourceFailureException`도, "java.util"은 `ConcurrentModificationException`도 잡는다. Java API의 checked 예외는 CGLIB(@Throws 유무 무관)에서 커밋되고 호출자는 원래 예외를 받는다. JDK Proxy는 호출자가 `UndeclaredThrowableException`을 받지만 행은 커밋. ALL_EXCEPTIONS면 0행, 그 위의 `noRollbackFor`는 1행. 직접 선언해도 Proxy는 CGLIB(Boot `AopAutoConfiguration$ClassProxyingConfiguration`이 남아 있다) | ② 롤백은 RuntimeException에만 걸리고, Kotlin 도메인 예외는 커밋된다(본문, `[e7]`) › Kotlin에서 checked 예외가 자동 롤백된다는 조언은 5.2.0~5.2.14·5.3.0~5.3.2의 동작이다(`[e7d]`) · rollbackOn = ALL_EXCEPTIONS는 기준선 자체를 옮긴다(`[e7b]`·`[e7c]`·`[e7e]`)<br>코드에서 고쳐야 하는 것 › 롤백 기준선은 설정 한 줄로 정한다(`[e7b]`) |
| e8 | `e8.log` | `[e8]` `[e8b]` | 시작·완료는 `TransactionInterceptor` TRACE, 새로 열기·참여·중단·rollback-only는 `JdbcTransactionManager` DEBUG. 자기 호출은 아무 줄도 없다. 7.0.3+ `MethodRollbackEvent`가 TRACE에서 스택과 함께 찍힌다. root=INFO 부팅은 CGLIB 경고 3줄(public final 2, 인터페이스 구현 final 1), root=WARN은 0줄 | 붙인 다음 운영에서 보는 것 › 부팅 로그의 CGLIB 경고는 root 레벨에 따라 사라진다(`[e8b]`) · Transaction 로그는 두 logger에 나뉘어 있다(`[e8]`) |
| e9 | `e9.log` | `[e9]` | aspectj가 classpath에 있으면 생성기가 `AnnotationAwareAspectJAutoProxyCreator`로 바뀐다. Aspect `@Order` 없음(둘 다 LOWEST)·Transaction이 더 작은 order면 '락 해제 → 커밋', Aspect가 LOWEST-1이면 '커밋 → 락 해제' | @Transactional 한 줄이 실제로 바꾸는 것(생성기 승격)<br>⑥ 같은 메서드의 다른 Advice와의 순서는 order가 정한다 › order가 작을수록 바깥이고, 지정하지 않으면 순서가 정해지지 않는다<br>코드에서 고쳐야 하는 것 › 락·재시도·라우팅 Aspect에는 @Order를 명시한다(요지 코드) |
| e10 | `e10.log` | `[e10]` | `@Component` 리스너가 Boot 자동 구성 TM에 붙는다. 외부 호출 begin/commit 1쌍, 자기 호출 0, REQUIRED 참여에는 이벤트가 없다. 바깥 TransactionTemplate 안에서는 자기 호출도 `active=true name=test-outer`라 구분되지 않는다 | 붙인 다음 운영에서 보는 것 › TransactionExecutionListener로 begin과 rollback을 센다<br>코드에서 고쳐야 하는 것 › 규칙은 정적 검사로, 우회는 @Transactional 없는 테스트로 잡는다(`[e10]` 대조) |
| e11 | `e11.log` | `[e11]` | coroutines-core만: 첫 suspension까지만 Transaction, 그때 커밋되고 이후 쓰기는 Transaction 밖 → 예외 뒤 2행 남음. +coroutines-reactor: 본문이 돌기 전에 빈 Transaction이 begin·commit되고 본문 전체가 밖 → 2행 남음. 대조(suspend fun → withContext(IO) → 일반 @Transactional fun)는 0행 | ⑤ › Coroutine에서는 Thread를 바꾸기 전에 Transaction 경계를 닫는다 |
| e12 | `e12.log` | `[e12]` | readOnly 메서드가 받은 DB: 라우팅 직접 WRITER, LazyConnectionDataSourceProxy로 감싸면 READER, setReadOnlyDataSource도 READER | ⑥ › DataSource 라우팅은 커넥션을 얻기 전에 정해져야 한다 |

실행하지 않은 것: ASPECTJ 위빙(컴파일/로드 타임 위빙이 필요해 돌리지 않았다, 본문은 문서 기준). JPA(Hibernate) 경로의 NESTED·rollback-only 메시지(이 저장소에 JPA가 없다, 본문은 v7.0.9 소스 기준).

## 커밋

이 폴더, `../txorder/`·`../txco/`, `src/main/java/com/petercoders/blog/tx/`, `build.gradle.kts` 변경은 커밋되지 않은 상태로 둔다. `runs/e6b.log`는 `e6.log`의 `[e6b]`로 합쳐 지웠다(삭제도 같이 커밋한다). `git add`/`commit`/`push`는 사용자가 직접 한다.
