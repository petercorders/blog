# exc — 3편 「@RestControllerAdvice: 전역 예외 처리가 덮지 않는 것」 실험 코드

velog 글 **"@RestControllerAdvice: 전역 예외 처리가 덮지 않는 것"**(SPRING-INTERNALS 시리즈 3편, 게시 후 링크로 교체)에 실린 실측 전부의 소스와 원본 로그다. 본문에는 요지 코드와 결정적 출력 몇 줄만 싣고, 돌아가는 전체 코드와 로그는 여기에 둔다.

판정은 시간이 아니라 결과다. 내장 Tomcat 을 실제로 띄우고 RestClient 로 요청을 보내 **상태 코드와 응답 바디**를 그대로 찍는다. 로그 줄은 "남는가"를 볼 때만 증거로 쓴다(X7+·X8b·X14 는 root Logger 에 붙인 수집 Appender 로 요청마다 로그 이벤트 수를 센다). 이식 가능한 것은 상태 코드·바디 형식·승패 관계뿐이고, 타임스탬프·스레드 이름·traceId 값은 이 실행에 묶여 있다.

## 실행 환경

| 항목 | 값 |
|---|---|
| CPU | Apple M5 Pro, physicalcpu 15 / logicalcpu 15, `hw.cachelinesize` 128 |
| OS | macOS, Darwin 25.6.0 |
| 실행 JVM | Gradle 툴체인 25 = Microsoft OpenJDK 25.0.4.1 (build 25.0.4.1+1-LTS, 2026-08-18). 새 마커 로그는 머리 `# java -version` 줄과 본문 첫 `env:` 줄(`java.runtime.version=25.0.4.1+1-LTS java.vendor=Microsoft`)에 실행 JVM 을 직접 찍는다 |
| 셸 기본 JVM(참고) | `/usr/bin/java` = OpenJDK 26.0.2.1 (2026-08-18). x1~x6 로그 머리의 26.0.2.1 은 이 셸 기본값이고 실행 JVM 이 아니다. 실행 JVM 이 25 라는 근거는 로그 안에도 있다. x3·x5·x7·x8·x9.log 스택의 `java.base/java.lang.Thread.run(Thread.java:1474)` 는 JDK 25.0.4.1 `src.zip` 의 `runWith(bindings, task);` 줄이고, JDK 26.0.2.1 에서는 같은 줄이 1516 이다(2026-10-10 대조). 같은 날 x1~x6.log 머리 둘째 줄 끝에 이 메모를 덧붙였다(줄 수·실행 출력은 그대로). x1.log 머리 둘째 줄(실행 뒤 덧붙인 `#` 주석)의 날짜는 처음에 `2026-09-27` 로 잘못 적었고, 2026-10-08 에 그 주석 줄만 실제 `java -version` 값 `2026-08-18` 로 고쳤다(줄 수·실행 출력은 그대로) |
| Kotlin | 2.3.21 |
| Spring Boot | 4.1.1 → Spring Framework 7.0.9, Spring Security 7.1.1, Tomcat 11.0.24 (새 마커의 `env:` 줄) |
| DB | H2 인메모리 |
| 포트 | `server.port=0`(임의 포트, `local.server.port` 로 읽는다). 다른 편 실험과 동시에 돌아도 부딪히지 않는다. x1~x9.log 는 고정 포트 18099 시절에 찍었다 |

`build.gradle.kts` 에서 이 글과 관계있는 것:

- `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-jdbc` (main).
- **`excLabExtra` 구성** — `spring-boot-starter-validation`(X10), `tools.jackson.module:jackson-module-kotlin`(X10 (d)), `spring-test`(X15 MockMvc). `excLab` 태스크 classpath 에만 붙고 main runtimeClasspath 에는 없다. 2·4·5편 러너의 Bean·Filter 덤프를 바꾸지 않기 위해서다. 컴파일용으로 `compileOnly` 에 `jakarta.validation-api`·`spring-test`·`micrometer-core` 가 있다.
- **`excLabObs` 구성과 같은 이름의 태스크** — `excLabExtra` + `spring-boot-starter-actuator`, `spring-boot-micrometer-tracing-brave`, `micrometer-tracing-bridge-brave`. X14OBS(선택 실험)만 이 태스크로 돈다.
- `excLab` 태스크에는 validation 이 들어 있으므로 Boot 의 `MethodValidationPostProcessor` 가 켜진다. X10 밖의 새 마커 결과에는 영향이 없다(표준 예외·Filter·Security 경로만 잰다).

## 파일

| 파일 | 내용 |
|---|---|
| [`ExcLab.kt`](ExcLab.kt) | 러너 `main()`·`boot()`. 마커 하나가 `step` 하나다. X1~X9 실행 함수 |
| [`ExcProbes.kt`](ExcProbes.kt) | X1~X9 프로브: `TraceFilter`·`BoomFilter`, `ExcController`, `AdviceA`(@Order(1))·`AdviceB`(@Order(2))·`InheritOnlyAdvice`, `AsyncProbe`, `ExcService`, permitAll Security 체인 |
| [`ExcProbesPlus.kt`](ExcProbesPlus.kt) | 2026-10 재편에서 더한 프로브(X2b·X3+·X7+·X8b·X10~X13·X16, 감사 반영 X11CTX·X7C·X11AFTER·X1UP·X8IDEM). 프로파일 목록은 파일 머리 주석 |
| [`ExcLabPlus.kt`](ExcLabPlus.kt) | 새 마커 러너(X1+·X2b·X3+·X5b·X7+·X8b·X10~X14·X16·X11CTX·X7C·X11AFTER·X1UP·X8IDEM)와 공통 도구(`call`, `envHeader`, `LogCapture`) |
| [`ExcLabX15.kt`](ExcLabX15.kt) | X15 — MockMvc 대 실제 서버 |
| [`ExcLabObs.kt`](ExcLabObs.kt) | X14OBS — actuator 메트릭 태그와 MDC traceId(`excLabObs` 태스크) |

## 실험 목록

'본문 절' 열은 원고의 지금 `##`·`###` 제목 그대로다(2026-10-10 원고 기준). 한 실험을 여러 절이 쓰면 ` · ` 로 잇는다. 검증 메모(접힌 `<details>`)에서만 언급한 곳은 적지 않았다. 표의 행 28개가 `runs/` 로그 28개와 하나씩 맞고, 빠진 로그는 없다(X7b·X8c~e 는 각각 x7.log·x8b.log 안 구간).

| 마커 / 로그 | 무엇을 보나 | 본문 절 |
|---|---|---|
| X1 / `runs/x1.log` | `handlerExceptionResolver`(Composite) 안 Resolver 셋, advice order, FilterRegistrationBean order | @RestControllerAdvice가 받는 것은 doDispatch()의 try 안 예외뿐이다 · ① advice가 여럿이면 @Order가 구체성보다 먼저다 · ③ Filter에서 난 예외는 advice가 아니라 /error가 받는다 |
| X1+ / `runs/x1plus.log` | DispatcherServlet 이 실제로 묻는 Resolver 순서(`DefaultErrorAttributes` HIGHEST_PRECEDENCE → Composite 0), 컨테이너 Filter 의 order·dispatcher type(`springSecurityFilterChain` -100, 전부), Security Filter Chain 안 순서, REEH `@ExceptionHandler` 목록 20개(`MaxUploadSizeExceededException` 포함) | @RestControllerAdvice가 받는 것은 doDispatch()의 try 안 예외뿐이다 · ① advice가 여럿이면 @Order가 구체성보다 먼저다 · ② 표준 예외와 검증 실패까지 같은 바디로 내리려면 ResponseEntityExceptionHandler를 상속한다 · ③ Filter에서 난 예외는 advice가 아니라 /error가 받는다 · /error로 가는 ERROR dispatch도 인가를 탄다 · 기본 설정에서 advice가 처리한 예외는 ERROR 로그와 exception 태그에 남지 않는다 |
| X1UP / `runs/x1up.log` | 업로드 크기 초과(`max-file-size=1KB`에 4096바이트): advice 없음 413 빈 바디(Resolver 기록이 REQUEST·ERROR 두 번), REEH 상속 413 problem+json, Security 앞 Filter 가 `getParameter` 를 먼저 부를 때 Tomcat 11.0.24 의 `InvalidParameterException`(잡으면 advice 가 받고, 던지면 /error 413 JSON) | @RestControllerAdvice가 받는 것은 doDispatch()의 try 안 예외뿐이다 |
| X2 / `runs/x2.log` | Controller 로컬(521) 대 `@Order(1)` AdviceA(541) 대 `@Order(2)` AdviceB(561) | @RestControllerAdvice가 받는 것은 doDispatch()의 try 안 예외뿐이다 · ① advice가 여럿이면 @Order가 구체성보다 먼저다 |
| X2b / `runs/x2b.log` | 범위를 좁힌 advice(`assignableTypes`)가 405·404·415 를 받는가, `basePackages`·`assignableTypes` OR 결합(591), 415 매핑 단계 대 인자 해석 단계 | 범위를 좁힌 advice는 405·404를 받지 못한다 |
| X3 / `runs/x3.log` | advice 없음 대 `spring.mvc.problemdetails.enabled=true` 의 Bean·바디 | @RestControllerAdvice가 받는 것은 doDispatch()의 try 안 예외뿐이다 · ② 표준 예외와 검증 실패까지 같은 바디로 내리려면 ResponseEntityExceptionHandler를 상속한다 |
| X3+ / `runs/x3plus.log` | 구성 8개(설정 없음 / problemdetails / REEH 상속 / 둘 다 / catch-all 넷) × 400·405·404·ISE, `problemDetailsExceptionHandler` Bean 유무 | ② 표준 예외와 검증 실패까지 같은 바디로 내리려면 ResponseEntityExceptionHandler를 상속한다 · Exception catch-all을 따로 두면 표준 예외까지 삼킨다 |
| X4 / `runs/x4.log` | REEH 상속만 했을 때 400·405·404 problem+json, 405 의 PageNotFound WARN | 먼저 겪은 팀들이 남긴 기록 · ② 표준 예외와 검증 실패까지 같은 바디로 내리려면 ResponseEntityExceptionHandler를 상속한다 · 기본 설정에서 advice가 처리한 예외는 ERROR 로그와 exception 태그에 남지 않는다 · 클라이언트가 받는 에러 바디는 경계마다 다르다 · /error 바디 설정은 Boot 4에서 spring.web.error.*로 옮겨졌다 |
| X5 / `runs/x5.log` | Filter 예외 → /error 500 대 Controller 예외 → advice 541 (X6 도 이 실행에서 찍힌다) | ③ Filter에서 난 예외는 advice가 아니라 /error가 받는다 · 클라이언트가 받는 에러 바디는 경계마다 다르다 |
| X6 / `runs/x6.log` | `REQUEST` → `ERROR /error` 디스패치 기록(x5.log 의 `[X6]` 구간 발췌) | ③ Filter에서 난 예외는 advice가 아니라 /error가 받는다 |
| X5b / `runs/x5b.log` | 옛 키 `server.error.*` 대 새 키 `spring.web.error.*`(include-message·include-exception·include-stacktrace)의 /error 바디 키 | /error 바디 설정은 Boot 4에서 spring.web.error.*로 옮겨졌다 |
| X7 / `runs/x7.log` | `@Async` void / 기다리지 않은 Future / `join()` (X7b 도 이 실행에서 찍힌다) | ⑥ @Async 예외는 응답에 남지 않고, 기다리지 않은 Future는 로그에도 남지 않는다 |
| X7+ / `runs/x7plus.log` | 기다리지 않은 Future 의 `isCompletedExceptionally=true` 와 그 사이 로그 0건, void 대조군 1건 | ⑥ @Async 예외는 응답에 남지 않고, 기다리지 않은 Future는 로그에도 남지 않는다 |
| X7C / `runs/x7c.log` | `join()` 한 future(`CompletionException` ← `IllegalStateException`)를 advice 구성 둘로: AdviceA 의 `RuntimeException` root 매치(541) 대 `IllegalStateException` Handler 만 둔 advice 의 cause 매치(561, 인자는 cause 인 `IllegalStateException`) | ⑥ @Async 예외는 응답에 남지 않고, 기다리지 않은 Future는 로그에도 남지 않는다 |
| X8 / `runs/x8.log` | `TransactionSynchronization` 네 콜백에서 던질 때 status·commit 여부 | ⑤ commit 뒤 콜백이 실패하면 응답과 데이터가 어긋난다 |
| X8b / `runs/x8b.log` | [X8b] `@TransactionalEventListener` AFTER_COMMIT(200, rows 1, ERROR 1건)·BEFORE_COMMIT(541, rows 0)·`SynchronizationCallback.postProcessEvent` / [X8c] 트랜잭션 없이 publish(기본 0회, `fallbackExecution=true` 1회) / [X8d] afterCommit 실패 요청 두 번 → rows 2 / [X8e] `@Async` + BEFORE_COMMIT(200, rows 1) | ⑤ commit 뒤 콜백이 실패하면 응답과 데이터가 어긋난다 · AFTER_COMMIT 리스너의 실패는 응답에 드러나지 않는다 · 재시도하면 행이 두 번 남는다 |
| X8IDEM / `runs/x8idem.log` | afterCommit 실패 요청(commit 뒤 541)을 같은 `Idempotency-Key` 로 두 번: Stripe 식으로 5xx 까지 저장하는 멱등키 Filter 는 541 을 재생하고 rows 1, 5xx 를 저장하지 않으면 다시 실행돼 541·rows 2. 대조군(콜백 예외 없음)은 200 재생·rows 1 | 재시도하면 행이 두 번 남는다 |
| X9 / `runs/x9.log` | 다섯 경계(Controller/Filter/@Async/beforeCommit/afterCompletion) 종합 | 운영에서 보는 것 |
| X10 / `runs/x10.log` | 검증 실패 네 경로((a) `@Valid @RequestBody` (b) 파라미터 제약 (c) 클래스 `@Validated` (d) Kotlin non-null 누락·타입 오류) × 설정 없음/REEH/각각 adapt-constraint-violations | 검증 실패는 세 경로로 오고 Kotlin은 역직렬화 실패가 먼저 온다 |
| X11 / `runs/x11.log` | Security 기본 401·403 의 디스패치와 바디, ERROR dispatch 인가 허용 여부, 토큰 Filter 가 던질 때·EntryPoint 를 부를 때, `handlerExceptionResolver` 위임(범위 제한 시 빈 200), `@Component` Filter 이중 등록(평범한 Filter 2회), ErrorController 와의 조합 | @RestControllerAdvice가 받는 것은 doDispatch()의 try 안 예외뿐이다 · ④ 401·403은 advice가 아니라 Security Filter가 만든다 · 토큰 Filter의 예외는 Security 밖으로 새어 /error로 간다 · /error로 가는 ERROR dispatch도 인가를 탄다 · 포맷은 /error에서 맞추고, EntryPoint를 바꿀 때는 WWW-Authenticate를 지킨다 · 클라이언트가 받는 에러 바디는 경계마다 다르다 · /error와 Security 응답도 같은 ProblemDetail로 맞춘다 |
| X11CTX / `runs/x11ctx.log` | ERROR dispatch 인가 허용 없이 익명 401(빈 바디)·user 403(/error 바디)이 갈리는 이유: Security Filter Chain 앞(-110)·뒤(-50) 프로브로 dispatch 마다 요청 속성의 SecurityContext 와 `SecurityContextHolder` 를 찍고, ERROR dispatch 직전에 그 속성을 지운 변형(user 도 401 빈 바디). `BasicAuthenticationFilter` 저장소·`shouldNotFilterErrorDispatch()`·`AuthorizationFilter.filterErrorDispatch` 값 | ④ 401·403은 advice가 아니라 Security Filter가 만든다 |
| X11AFTER / `runs/x11after.log` | X11 (4)의 토큰 Filter(던짐)를 `addFilterAfter(ExceptionTranslationFilter)` 로 옮긴 변형: 체인 [10], ERROR dispatch 허용 시 익명·user 모두 401 + `WWW-Authenticate` + /error 바디, 막으면 익명 빈 바디·user /error 바디, Tomcat ERROR 로그 없음 | 토큰 Filter의 예외는 Security 밖으로 새어 /error로 간다 · 포맷은 /error에서 맞추고, EntryPoint를 바꿀 때는 WWW-Authenticate를 지킨다 |
| X12 / `runs/x12.log` | `BusinessException` → ProblemDetail properties(errorCode·traceId), `handleExceptionInternal` override 위치 두 가지(super 전 body 인자는 표준 예외에서 null → 안 붙음 / super 결과에 붙이면 STD_400), `@ResponseStatus` 예외(/error) 대 `ResponseStatusException`·`ErrorResponseException` 하위(problem+json) | errorCode는 커스텀 예외 계층에 두고 ProblemDetail properties로 내린다 |
| X13 / `runs/x13.log` | `ErrorAttributes` 교체(키만 바뀌고 application/json) 대 `ErrorController` 교체(problem+json, `BasicErrorController` 빠짐), traceId Filter 네 변형 | /error와 Security 응답도 같은 ProblemDetail로 맞춘다 |
| X14 / `runs/x14.log` | root=WARN / root=INFO / `spring.mvc.log-resolved-exception=true` 에서 요청별 로그 이벤트 수 | 기본 설정에서 advice가 처리한 예외는 ERROR 로그와 exception 태그에 남지 않는다 |
| X14OBS / `runs/x14obs.log` | (선택) actuator `http.server.requests` 태그(advice 541 은 `exception=none`, `outcome=UNKNOWN`), Controller 와 `@ExceptionHandler` 안 MDC traceId, micrometer-tracing·brave jar 버전(`env:` 줄) | 기본 설정에서 advice가 처리한 예외는 ERROR 로그와 exception 태그에 남지 않는다 |
| X15 / `runs/x15.log` | 같은 요청을 MockMvc 와 실제 서버로: Filter 예외는 MockMvc 가 던지고, 405·400 은 MockMvc 에서 빈 바디 | 에러 응답은 실제 서버로 테스트한다 |
| X16 / `runs/x16.log` | `@PreAuthorize` 거부(`AuthorizationDeniedException`)를 advice 가 먼저 잡는가: advice 없음 401/403, AdviceA 541, catch-all 599, 되던지는 catch-all 401/403 | @PreAuthorize 거부는 try 안에서 나서 catch-all advice가 먼저 잡는다 |

## 실행

메인 클래스는 `com.petercoders.blog.exc.ExcLabKt`. 마커를 인자로 주면 그 마커 하나만 돌고 결과는 표준 출력에 찍힌다(인자가 없으면 X14OBS 를 뺀 전부). 그래서 로그 파일 하나에 실행 한 번이다. 마커와 파일 이름은 소문자로 같고, 예외는 셋이다. X5 실행은 `[X6]` 구간까지 찍어 그 구간을 `x6.log` 로 잘라 냈고, X7 실행이 X7b 를, X8B 실행이 X8c·X8d·X8e 를 함께 찍는다. 로그 머리의 `#` 줄은 실행 뒤 덧붙인 메모다.

```bash
cd ~/Desktop/development/blog && R=src/main/kotlin/com/petercoders/blog/exc/runs
for m in X1 X2 X3 X4 X5 X7 X8 X9 X1PLUS X2B X3PLUS X5B X7PLUS X8B X10 X11 X12 X13 X14 X15 X16 X11CTX X7C X11AFTER X1UP X8IDEM; do
  ./gradlew --offline -q excLab --args="$m" > $R/$(echo $m | tr 'A-Z' 'a-z').log 2>&1; done
sed -n '/\[X6\]/,$p' $R/x5.log > $R/x6.log
./gradlew --offline -q excLabObs --args='X14OBS' > $R/x14obs.log 2>&1
```

새 마커 로그(`x1plus`·`x2b`·`x3plus`·`x5b`·`x7plus`·`x7c`·`x8b`·`x10`~`x16`·`x11ctx`·`x14obs`)는 2026-10-08 에, 감사 반영 마커 로그(`x11after`·`x1up`·`x8idem`)는 2026-10-10 에 저장소를 `rsync` 한 사본(`/tmp/gradle-exc`)에서 위 명령으로 찍었다. 로그 머리 넷째 줄 `# command:` 에 실행 시각이 있다. `excLabExtra`·`excLabObs` 의존은 캐시에 없어서 처음 한 번만 `--offline` 없이 의존을 받아 왔고, 로그는 전부 `--offline` 으로 찍었다.

## `runs/` 로그 색인

| 로그 | 마커 | 비고 |
|---|---|---|
| `runs/x1.log` | X1 | Resolver·advice·Filter 실물. 머리 둘째 줄 주석의 날짜 오기(`2026-09-27`)를 2026-10-08 에 `2026-08-18` 로 고쳤다. 주석 줄만 고쳤고 줄번호·실행 출력은 그대로다 |
| `runs/x1plus.log` | X1+ | Resolver·Filter order·dispatcher type, REEH 목록 |
| `runs/x1up.log` | X1UP | 업로드 크기 초과가 try 안에서 나는가(2026-10-10 14:14, 감사 반영) |
| `runs/x2.log` | X2 | 로컬/AdviceA/AdviceB 승자 |
| `runs/x2b.log` | X2b | advice 범위 |
| `runs/x3.log` | X3 | problemdetails on/off |
| `runs/x3plus.log` | X3+ | 구성 8 × 요청 4, 끝에 요약 매트릭스 |
| `runs/x4.log` | X4 | REEH 상속만 했을 때 |
| `runs/x5.log` | X5, X6 | Filter 예외 + 디스패치 기록 |
| `runs/x5b.log` | X5b | 옛 키 대 새 키 |
| `runs/x6.log` | X6 | x5.log 의 `[X6]` 이후 구간 발췌 |
| `runs/x7.log` | X7, X7b | `@Async` 반환 타입 세 갈래 + 핸들러 교체 카운터 |
| `runs/x7plus.log` | X7+ | 기다리지 않은 Future |
| `runs/x7c.log` | X7C | root 매치 없을 때 cause 매치(2026-10-08 10:07, 감사 반영) |
| `runs/x8.log` | X8 | 동기화 콜백 네 단계 |
| `runs/x8b.log` | X8b, X8c, X8d, X8e | `@TransactionalEventListener`, fallbackExecution, 재시도, @Async + BEFORE_COMMIT |
| `runs/x8idem.log` | X8IDEM | 멱등키 레이어가 commit 뒤 541 을 저장할 때와 저장하지 않을 때(2026-10-10 14:35, 감사 반영) |
| `runs/x9.log` | X9 | 다섯 경계 종합 |
| `runs/x10.log` | X10 | 검증 실패 경로 |
| `runs/x11.log` | X11 | Security 401·403 |
| `runs/x11ctx.log` | X11CTX | ERROR dispatch 의 SecurityContext 출처(2026-10-08 10:07, 감사 반영) |
| `runs/x11after.log` | X11AFTER | 토큰 Filter 를 ExceptionTranslationFilter 뒤에(2026-10-10 14:15, 감사 반영) |
| `runs/x12.log` | X12 | errorCode·traceId |
| `runs/x13.log` | X13 | /error 통일과 traceId |
| `runs/x14.log` | X14 | 처리된 예외의 로그 |
| `runs/x14obs.log` | X14OBS | 메트릭 태그·MDC traceId(선택). 2026-10-08 09:25 에 다시 찍어 `env:` 줄(l.10)에 micrometer-tracing·bridge-brave 1.7.1, brave 6.3.1 jar 를 남겼다 |
| `runs/x15.log` | X15 | MockMvc 대 실제 서버 |
| `runs/x16.log` | X16 | `@PreAuthorize` 거부와 catch-all |

X11AFTER·X1UP·X8IDEM 은 2026-10-10 에 더한 마커다. 코드는 `ExcLab.kt`·`ExcLabPlus.kt`·`ExcProbesPlus.kt` 에, 로그는 `runs/x11after.log`·`runs/x1up.log`·`runs/x8idem.log` 에 있다. 같은 날 x1~x6.log 머리 둘째 줄 끝에 덧붙인 JVM 메모는 실행 뒤 주석이고 줄 수·실행 출력은 그대로다.
