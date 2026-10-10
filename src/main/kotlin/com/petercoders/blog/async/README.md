# async — 5편 "@Async·@Scheduled: 붙이기 전에 확인할 것" 실험 코드

SPRING-INTERNALS 5편(`@Async`·`@Scheduled`)에 실린 실측의 소스와 원본 로그를 담는다.
본문(velog 게시 후 이 줄에 링크 추가 예정)에는 요지 코드와 결정적 출력 몇 줄만 실려 있고, 전문은 여기에 있다.
아래 실험 표의 "본문 절" 열은 원고의 지금 절 제목(2026-10-08 판)과 맞췄다.

## 실행 환경

| 항목 | 값 |
|---|---|
| 기기 / CPU | Apple M5 Pro, 15 코어 |
| 캐시라인 | `hw.cachelinesize` 128 |
| OS | macOS, Darwin 25.6.0 |
| JDK(실행 JVM) | Microsoft OpenJDK 25.0.4.1+1-LTS (Gradle 툴체인 `languageVersion=25`). 2026-10-08 부터 모든 로그 머리의 `# runtime:` 줄에 프로그램이 직접 찍은 `Runtime.version()`·`java.vendor`·`java.home` 이 있다. 셸 기본 `java -version`(26.0.2.1)은 실행 JVM 이 아니다 |
| Kotlin | 2.3.21 (`KotlinVersion.CURRENT`) |
| Spring Boot / Framework | 4.1.1 / 7.0.9 (`SpringBootVersion`·`SpringVersion`) |
| DB | H2 인메모리 (`jdbc:h2:mem:asynclab;DB_CLOSE_DELAY=-1`, 사용자 `sa`) |

## 파일

| 파일 | 내용 |
|---|---|
| `AsyncLab.kt` | 실험 드라이버. 마커마다 `runXxx()`. 로그 머리에 런타임 버전을 찍는다 |
| `AsyncProbes.kt` | `@Async` 프로브 빈(`AsyncProbe`), 카운터·H2 헬퍼, A3/A3c 의 `@Scheduled` 프로브, A1c 의 `ExecutorService` 빈 |
| `AsyncProbes2.kt` | 2차 실험(A1d~A6)의 프로파일별 Bean·프로브. `@EnableScheduling` 은 여기의 `SchedulingOn`(`@Profile("!nosched")`)으로 옮겼다 |
| `AsyncCtx.kt` | [A2eCtx] `io.micrometer:context-propagation` 이 있을 때만 도는 코드(`ContextRegistry`) |
| `AsyncObs.kt` | [A6] actuator 가 있을 때만 도는 코드(`MeterRegistry`) |
| `a4f_sigterm.py` | [A4f] java 를 직접 띄우고 실제 SIGTERM 을 보내는 드라이버 |

실험용 의존(`context-propagation`, `spring-boot-starter-actuator`)은 `build.gradle.kts` 의 `asyncLabCtx`·`asyncLabObs`
configuration 에만 있고 main runtimeClasspath 에는 없다(A2e 의 'context-propagation 없음' 전제와 다른 편 실험의 전제를 지킨다).
컴파일은 `compileOnly` 로만 한다.

## 실험 목록

'본문 절' 열은 원고(`SPRING-INTERNALS/5-ASYNC-SCHEDULED-POST.md`, 2026-10-08 판)의 지금 `##`·`###` 제목 그대로다.
①~⑦은 문제별 `##` 절이고 `›` 뒤가 그 아래 `###` 절이다. `(본문)`은 그 `##` 절의 첫 `###` 앞 부분이다.
한 실험을 여러 절이 쓰면 `<br>` 로 잇고, 괄호는 그 절에서 이 실험이 맡은 부분이다. 그림 설명(alt)과 접힌 검증 메모에서만 언급한 곳은 적지 않았다.

- ① 기본 풀은 8개에서 멈추고 큐는 끝이 없다
- ② Executor Bean 하나가 Boot Executor를 지운다
- ③ void 예외는 로그 한 줄로 끝나고, 타임아웃은 작업을 멈추지 않는다
- ④ 스레드가 바뀌면 MDC도 Transaction도 따라오지 않는다
- ⑤ @Scheduled는 스레드 하나를 모든 잡이 나눠 쓴다
- ⑥ 같은 잡이 동시에 두 번 도는 경로는 둘이다
- ⑦ 종료는 실행 중인 작업이 0이 되는 순간까지만 기다린다

본문이 링크한 저장소 파일은 `runs/` 로그 25개와 `a4f_sigterm.py` 이고, 모두 아래 표의 '로그' 열에 있다.
`runs/A3.log`·`runs/A3b.log`(2026-09-28 원본)는 본문이 링크하지 않는다. 본문은 런타임 머리와 close 시각이 있는 `-rerun` 로그를 인용한다.

| 마커 | 무엇을 보나 | 로그 | 본문 절 | 결과 요지 |
|---|---|---|---|---|
| A1 / A1b | Virtual Thread off/on — `applicationTaskExecutor`·`taskScheduler` 실물과 풀 설정 | `runs/A1.log` | @Async·@Scheduled 한 줄이 실제로 올라타는 것 | off: `ThreadPoolTaskExecutor core=8 max=2147483647 queueCapacity=2147483647`, `task-1` / on: `SimpleAsyncTaskExecutor`·`SimpleAsyncTaskScheduler`, `task-1 virtual=true` |
| A1c | 사용자 `ExecutorService` Bean 등록 / `mode=force` | `runs/A1c.log` | ② (본문)<br>② › 용도별 Executor를 남기는 방법은 둘이다(`[A1c-force]`) | `applicationTaskExecutor` 없음, `@Async` 는 `scheduling-1` / force 면 `task-1` |
| A1d | 풀과 큐: latch 로 붙잡고 poolSize·activeCount·queueSize / 거절 정책 Customizer | `runs/A1d.log` | 도입(배경 문단)<br>먼저 써 본 팀들이 남긴 기록(갱신 문단)<br>① › max-size는 큐가 찬 뒤에야 쓰인다<br>① › 큐를 줄이면 거절 정책을 같이 고른다(`[A1d-3]`·`[A1d-5]`)<br>⑦ › 큐에 남은 작업은 보장되지 않는다(거절 메시지의 `CompletableFuture$AsyncSupply`) | 기본 20건 → pool 8·queue 12 / cap 4·max 16 → pool 16·queue 4 / 30건 → `TaskRejectedException` 10건 / cap 0 → 16 실행·4 거절 / (5) `ThreadPoolTaskExecutorCustomizer` 로 CallerRunsPolicy → 포화 중 `whereAmI()` 가 `main` 에서 돈다 |
| A1e | Executor Bean 8가지 × `@EnableScheduling` 유무 | `runs/A1e.log` | ② (본문, 여덟 조합 표)<br>② › 용도별 Executor를 남기는 방법은 둘이다(`[A1e-vi]`) | ExecutorService+sched → `scheduling-1`, ExecutorService 단독 → `SimpleAsyncTaskExecutor-1`, `myPool`+sched → `SimpleAsyncTaskExecutor-1`(INFO 'More than one TaskExecutor …'), `myPool` 단독 → `my-1`, 이름 `taskExecutor` → `te-1`, `defaultCandidate=false` → `task-1` + `report-1`, force → `task-1`, `@Primary` → `my-1` |
| A2 | `get()`·`join()`·`Future.get()` 세 갈래의 예외 클래스명, `void` 예외 | `runs/A2.log` | ③ › 대기 방식이 예외의 행선지를 정한다<br>붙인 다음 운영에서 보는 것(알림 로그 `void` 줄) | `ExecutionException`/`CompletionException`/`ExecutionException`, `void` 는 `SimpleAsyncUncaughtExceptionHandler` ERROR 한 줄 |
| A2c | `get(1, SECONDS)` 타임아웃 | `runs/A2c.log` | ③ › 타임아웃은 기다림만 끊는다 | `CompletableFuture`, 원인 없는 `TimeoutException`, 타임아웃 뒤 `isDone=false` |
| A2d | 타임아웃 뒤 `cancel(true)` — 반환 타입별 | `runs/A2d.log` | ③ › 타임아웃은 기다림만 끊는다 | CF: cancel=true 인데 작업은 끝까지(finished=1) / `Future`(FutureTask): interrupted=1 / `orTimeout`: 작업은 끝까지 |
| A2e / A2eCtx | MDC·SecurityContext·Transaction 전파, TaskDecorator 가 붙는 Executor, propagate-context | `runs/A2e.log` | ④ (본문, `[A2e-a]`)<br>④ › TaskDecorator는 Boot builder로 만든 Executor에만 붙는다(`[A2e-b]`~`[A2e-f]`) | 기본: 셋 다 비어 있음 / TaskDecorator Bean: `applicationTaskExecutor`·builder Executor 에만 붙고 `new` Executor 에는 안 붙음 / propagate-context: 의존 없으면 Bean 없음, 의존 있으면 SecurityContext 만 넘어오고 MDC 는 accessor 등록 뒤에만 / txActive 는 모든 경우 false |
| A2f | 같은 풀 안에서 결과 기다리기(core 2) | `runs/A2f.log` | 코드에서 고쳐야 하는 것 › 같은 풀 안에서 결과를 기다리지 않는다 | outer 2건 모두 3초 뒤 `TimeoutException` / inner 를 다른 Executor 로 나누면 2ms |
| A2g | Transaction 안의 `@Async` / AFTER_COMMIT 리스너 / 롤백 / Transaction 밖 publish | `runs/A2g.log` | ④ › 커밋 뒤에 돌아야 하는 작업은 이벤트로 넘긴다 | `@Async` 는 커밋 전에 돌아 rows=0, AFTER_COMMIT `@Async` 리스너는 rows=1 / 롤백: `@Async` 부수효과 1회, 리스너 0회 / Transaction 밖: fallbackExecution 리스너만 |
| A3 / A3-rerun | 기본 `pool.size=1`, `fixedRate` 2s · 본문 3s | `runs/A3-rerun.log`(2026-10-08, close 시각 포함, 본문 인용), `runs/A3.log`(2026-09-28 원본) | 먼저 써 본 팀들이 남긴 기록(⑤는 A3 실측이 채운다)<br>⑤ › 밀린 실행은 신호 없이 늦어진다 | 한 스레드가 번갈아 3초씩, 겹침 없음, 밀림 +3 → +4 → +7초. 재실행은 시작 잡과 오프셋만 다르고 모양은 같다 |
| A3b / A3b-rerun | `pool.size=2` 비교군 | `runs/A3b-rerun.log`(본문 인용), `runs/A3b.log`(원본) | ⑤ › 밀린 실행은 신호 없이 늦어진다 | 두 잡이 따로 3초 주기, 둘째 회차부터 스레드가 뒤바뀐다, close 는 돌던 넷째 회차가 끝난 뒤 반환. 재실행은 오프셋만 다르다 |
| A3c | Virtual Thread on — `fixedRate` 대 `fixedDelay` | `runs/A3c.log` | ⑥ › Virtual Thread를 켜거나 잡에 @Async를 붙이면 같은 잡이 겹친다<br>⑦ › Virtual Thread를 켜면 @Async 작업을 기다리지 않는다(close 시각) | fixedRate(delta) 겹침, fixedDelay(gamma) 안 겹침. close 는 gamma 가 끝날 때(14,458ms)까지 반환하지 않았다 |
| A3d | `@Scheduled` 예외 뒤 다음 주기, ERROR 로그, `lastExecutionOutcome` | `runs/A3d.log` | ⑤ › 예외가 나도 다음 주기는 돈다<br>붙인 다음 운영에서 보는 것(알림 로그 `@Scheduled` 줄) | 4.5초에 5회 실행(1·3·5회 Boom), `TaskUtils$LoggingErrorHandler` ERROR, `status=ERROR` |
| A3e | Virtual Thread 스케줄러 겹침과 제한 수단 / Virtual Thread off + `@Async @Scheduled` | `runs/A3e.log` | ⑥ › Virtual Thread를 켜거나 잡에 @Async를 붙이면 같은 잡이 겹친다<br>⑦ › Virtual Thread를 켜면 @Async 작업을 기다리지 않는다(`[A3e-0]` close 8ms) | (0) fixedRate·cron 모두 겹침, close 는 기다리지 않음 / (a) 스케줄러 전체가 한 줄로(zeta 도 밀림) / (b) `@ConcurrencyLimit(1)` 겹침 없이 줄 서고 close 뒤에도 대기분이 계속 시작 / (c) REJECT: 겹치는 회차가 `InvocationRejectedException` ERROR 로 빠짐 / (d) `task-` 스레드에서 겹침 |
| A4 | 종료 기본값 — 실행 중 `@Async`(4초), 1초 뒤 close | `runs/A4.log` | ⑦ › 기본값은 실행 중 작업을 phase timeout까지 기다린 뒤 interrupt한다 | close 호출 t=1,007ms → 반환 t=4,040ms(3,033ms), finished=1 |
| A4b | `await-termination=true` + `period=10s` | `runs/A4b.log` | ⑦ › await-termination은 기다리는 단계를 바꾼다 | close 호출 t=1,006ms → 반환 t=4,031ms(3,025ms), finished=1 |
| A4c | `timeout-per-shutdown-phase=2s` / 실행 중 `@Scheduled` 잡 종료 | `runs/A4c.log` | ⑦ › 기본값은 실행 중 작업을 phase timeout까지 기다린 뒤 interrupt한다<br>붙인 다음 운영에서 보는 것(알림 로그 종료 timeout 줄) | 2s: 'Shutdown phase 1073741823 ends with 1 bean still running after timeout of 2000ms', interrupted=1, h2Rows=0 / 스케줄 잡: 30s 면 끝까지, 2s 면 interrupt |
| A4d | `await-termination` 조합 / `accept-tasks-after-context-close` | `runs/A4d.log` | ⑦ › await-termination은 기다리는 단계를 바꾼다 | period 1s: 1초 뒤 WARN 'Timed out while waiting …', 작업은 interrupt 없이 계속 / period 없음: 즉시 반환, 작업 계속 / accept=true: 즉시 반환 + interrupt |
| A4e | Virtual Thread on 종료 4조건 | `runs/A4e.log` | 먼저 써 본 팀들이 남긴 기록(갱신 문단)<br>⑦ › Virtual Thread를 켜면 @Async 작업을 기다리지 않는다 | 기본·await 만: close 즉시 반환(기다리지 않음) / period 10s: 작업 끝까지 기다림 / cancel-remaining: 즉시 interrupt |
| A4f | 실제 SIGTERM(web, phase 3s) — platform / Virtual Thread | `runs/A4f.log`, 드라이버 `a4f_sigterm.py` | 먼저 써 본 팀들이 남긴 기록(갱신 문단)<br>⑦ › Kubernetes 예산 안에 종료 phase가 여럿 들어간다(platform)<br>⑦ › Virtual Thread를 켜면 @Async 작업을 기다리지 않는다(vt) | platform: SIGTERM→종료 6,142ms(웹 phase 3s + Executor phase 3s), 작업 interrupt / Virtual Thread: 4,198ms, `@Async` 작업은 interrupt 로그도 마커도 없이 사라짐 |
| A4g | 큐에 남은 작업(워커 수별) / CF 갈래 | `runs/A4g.log` | 먼저 써 본 팀들이 남긴 기록(갱신 문단)<br>⑦ › 큐에 남은 작업은 보장되지 않는다<br>⑦ › await-termination은 기다리는 단계를 바꾼다(넷째 행 참조) | core 1: started=2·finished=1·interrupted=1 / core 2 엇갈림: 6건 모두 완료(7.5s) / core 8·20건: 모두 완료(6.0s) / await+period: 3건 모두 / CF: 큐에서 버려진 작업의 호출자 future 는 끝내 미완료 |
| A5 | 자기 호출 / 자기 주입 | `runs/A5.log` | 코드에서 고쳐야 하는 것 › 자기 호출은 비동기로 돌지 않는다 | `this.whereAmI()` → `main` / allow-circular-references=true 필드 자기 주입: 부팅은 되지만 주입된 값은 Proxy 가 아닌 원본(호출하면 `main`) / `@Lazy` 자기 주입 → `task-1` |
| A6 | executor 메트릭 자동 바인딩(actuator) | `runs/A6.log` | 붙인 다음 운영에서 보는 것 | name 태그 = Bean 이름. `applicationTaskExecutor`·`taskScheduler`·`reportExecutor`(defaultCandidate=false 도 포함) / ExecutorService·Virtual Thread Executor 는 빠짐 / `executor.queued`=12.0 = `getQueueSize()` 12 |
| E10 | 버전 사실 대조(실행 없음): Framework·Boot 태그 소스, JDK 25 `src.zip`, GitHub 이슈 milestone | `runs/E10-version-checks.log` | ⑦ (본문, 버전 표) | coordinated stop 6.1.0, `markShutdown`·`strictEarlyShutdown` 6.1.4, `DEFAULT_PHASE = Integer.MAX_VALUE / 2` 6.2.0, Framework 단독 phase timeout 30초 → 10초 6.2.0, 로그 문구 'Failed to shut down' → 'Shutdown phase … ends with' 6.1.4, `cancel(false)` 6.0.12, `defaultCandidate` 6.2.0(Boot 3.4.0 = Framework 6.2.0), 스케줄러 builder 의 `TaskDecorator` Boot 3.5.0, milestone #27090 6.1.0-M2 · #31019 6.0.12 · #32109·#32226 6.1.4 |

## 실행 명령

메인 드라이버는 `com.petercoders.blog.async.AsyncLabKt`(`build.gradle.kts` 의 `asyncLab`·`asyncLabCtx`·`asyncLabObs` 태스크)다.
2026-10-08 실행은 저장소를 `rsync -a --delete --exclude build --exclude .gradle` 로 `/tmp/gradle-async/` 에 복사한 사본에서 했다
(다른 편 실험과 동시에 돌기 때문). 명령은 각 로그의 `# command:` 줄에 그대로 있다.

```bash
cd ~/Desktop/development/blog
./gradlew --offline -q asyncLab --args='A1d'            # 마커 하나
./gradlew --offline -q asyncLab --args='A4g 1 2 3'      # 마커 안의 조건 일부
./gradlew --offline -q asyncLabCtx --args='A2eCtx'      # context-propagation 이 있는 클래스패스
./gradlew --offline -q asyncLabObs --args='A6'          # actuator 가 있는 클래스패스
CP=$(./gradlew --offline -q asyncLabClasspath)          # A4f: java 직접 실행 + SIGTERM
python3 src/main/kotlin/com/petercoders/blog/async/a4f_sigterm.py "$(/usr/libexec/java_home -v 25)/bin/java" "$CP" platform
```

A3e 는 조건마다 JVM 을 따로 띄웠다(`--args='A3e 0'`, `'A3e a'` …). VT 스케줄러는 close 때 fixedRate·cron 작업을 기다리지 않아서
한 JVM 에서 이어 돌리면 앞 조건의 작업 줄이 다음 조건 로그에 섞인다(처음 한 번 그렇게 섞인 실행은 버렸다).
절대 시간(ms)은 이 기계를 탄다. 판정은 스레드 이름·예외 클래스명·카운터·H2 행으로 한다.
macOS(APFS)는 대소문자 비구분이라 새 로그 파일을 만들 때 기존 파일명과 충돌하지 않는지 먼저 `ls` 로 확인한다.

## `runs/` 로그 색인

| 파일 | 마커 | 비고 |
|---|---|---|
| `runs/A1.log` | A1, A1b | 2026-10-08 재실행(값 동일, 런타임 머리 추가) |
| `runs/A1c.log` | A1c, A1c-force | 2026-10-08 재실행(값 동일) |
| `runs/A1d.log` | A1d-1~5 | 신규(A1d-5 는 같은 날 이어 붙인 실행) |
| `runs/A1e.log` | A1e-i~viii | 신규 |
| `runs/A2.log` | A2 | 2026-10-08 재실행(값 동일) |
| `runs/A2c.log` | A2c | 2026-10-08 재실행(값 동일, 반환 future 클래스 줄 추가) |
| `runs/A2d.log` | A2d | 신규 |
| `runs/A2e.log` | A2e-a~c, A2eCtx(d~f) | 신규 |
| `runs/A2f.log` | A2f | 신규 |
| `runs/A2g.log` | A2g | 신규 |
| `runs/A3.log` | A3 | 2026-09-28 원본(셸 JDK 26 머리). 재실행과 줄이 달라 남겨 둠 |
| `runs/A3-rerun.log` | A3 | 2026-10-08 재실행(close 시각 포함) |
| `runs/A3b.log` | A3b | 2026-09-28 원본. 재실행과 오프셋이 달라 남겨 둠 |
| `runs/A3b-rerun.log` | A3b | 2026-10-08 재실행 |
| `runs/A3c.log` | A3c | 2026-10-08 재실행(작업 줄은 원본과 같고 머리·close 시각 추가. 원본은 머리가 없었다) |
| `runs/A3d.log` | A3d | 신규 |
| `runs/A3e.log` | A3e-0, a, b, c, d | 신규(조건마다 JVM 따로) |
| `runs/A4.log` | A4 | 2026-10-08 재실행(원본 '3초' = 정수 절삭, 이번 3,033ms) |
| `runs/A4b.log` | A4b | 2026-10-08 재실행(값 동일) |
| `runs/A4c.log` | A4c-a, b30, b2 | 신규 |
| `runs/A4d.log` | A4d-1~3 | 신규 |
| `runs/A4e.log` | A4e-1~4 | 신규 |
| `runs/A4f.log` | A4f platform, vt | 신규 |
| `runs/A4g.log` | A4g-1~5 | 신규 |
| `runs/A5.log` | A5, A5-b, A5-c | 신규 |
| `runs/A6.log` | A6-1~4 | 신규 |
| `runs/E10-version-checks.log` | E10 | 실행 없음. 버전 사실을 Framework·Boot 태그 소스, JDK 25 src.zip, GitHub 이슈 milestone 으로 대조한 grep 원문 |

`fc6713c` 까지가 2026-09-28 실행분이다. 그 뒤의 변경(위 색인의 '재실행'·'신규' 로그, `AsyncProbes2.kt`·`AsyncCtx.kt`·`AsyncObs.kt`·`a4f_sigterm.py`, `build.gradle.kts` 의 `asyncLabCtx`·`asyncLabObs`·`asyncLabClasspath`)은 2026-10-08 실행분이다.
