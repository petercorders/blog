# tip — JVM 동시성 실무 (3편) 실험 코드

글: [JVM 동시성 실무: 무엇이 새는지는 코드가 정한다](https://velog.io/@petercoders/jvm-concurrency-practice) (게시 후 링크 갱신)

원고: `~/Desktop/blog/JAVA-KOTLIN-CONCURRENCY/3-CONCURRENCY-PRACTICE-POST.md`

이 디렉터리는 3편에서 새로 실행한 실험 17건(tip-p1~p9 5건, 2026-10-08 재작성 실험 tip-p11~p26 12건)의 소스와 로그만 담는다. 1·2편 실험(`jkc/`, `jvt/`, `kco/`, `kcolab/`)은 각자 디렉터리를 그대로 재사용한다. Spring·HikariCP 실험은 `spring/` 아래에 있고 Gradle sourceSet `tipSpring`(build.gradle.kts)으로 main 과 격리해 돌린다.

## 실행 환경

| 항목 | 값 |
|---|---|
| CPU | Apple M5 Pro, 15코어 |
| 아키텍처 | arm64 |
| 캐시라인 | 128B |
| OS | Darwin (macOS) |
| JDK (본편) | OpenJDK 26.0.2.1 (build 26.0.2.1+1-7) |
| JDK (비교용, tip-p15·p23·p25) | Microsoft OpenJDK 25.0.4.1 (build 25.0.4.1+1-LTS) |
| kotlinc (단독 실험) | 2.3.20 (IntelliJ IDEA 번들, JRE 26.0.2.1+1-7) |
| Kotlin Gradle plugin (tipSpring) | 2.3.21, 컴파일 toolchain 25, 실행 JDK 26 |
| Spring (tipSpring) | Spring Boot 4.1.1 BOM → Spring Framework 7.0.9, HikariCP 7.0.2, H2 2.4.240, Tomcat 11.0.24, kotlinx-coroutines-reactor 1.10.2, context-propagation 1.2.1(runtimeOnly, `spring.reactor.context-propagation` 은 설정하지 않음) |
| kotlinx-coroutines-core-jvm | 1.10.2 (tipSpring 도 Boot 4.1.1 BOM 으로 1.10.2) |
| Micrometer (tip-p22·p26) | micrometer-core 1.17.1, micrometer-java21 1.17.1 (Maven Central) |
| BlockHound (tip-p25) | blockhound 1.0.17.RELEASE + kotlinx-coroutines-debug 1.10.2 (kco-e24 와 같은 jar) |

절대 시간(ms)·스레드 수는 이 기기를 탄다. 다른 기기로 옮길 때는 절대값이 아니라 갈림의 방향과 비율만 이식 가능하다.

## 실험 목록

'본문 절' 열은 원고(2026-10-08 재작성판)의 지금 절 제목 그대로다. `①`~`④` 는 번호 절(`## ① …`), '운영' 은 `## 도입한 다음 운영에서 보는 것`, '코드' 는 `## 코드에서 고쳐야 하는 것` 이고, 그 뒤가 `###` 소단원 제목이다. 소단원 없이 번호 절 제목만 적은 행은 번호 절 본문(소단원 앞)이다. 로그 링크가 걸린 절을 먼저 적고, 링크 없이 실험 id 로 수치를 다시 쓰는 절은 '재인용' 으로 덧붙였다.

| 파일 | 무엇을 보나 | 본문 절 |
|---|---|---|
| `BlockingWorkers.kt` | `Dispatchers.Default` 워커가 sleep·소켓 read·중첩 `runBlocking`에 막힐 때 스레드 상태·톱 프레임이 어떻게 갈리는지 | ① 상태 열이 아니라 톱 프레임으로 판정한다 (본문은 같은 세 케이스를 포함한 tip-p11 을 쓴다) |
| `TimeoutSleep.kt` | `withTimeout(100)` 아래에서 그냥 sleep·`runInterruptible`+sleep·delay·플랫폼/가상 스레드의 소켓 read가 각각 언제 끝나는지 | ③ runInterruptible은 JDBC 소켓을 끊지 못한다 (재인용: ④ Virtual Thread Dispatcher는 블로킹은 받지만 상한과 ThreadLocal은 다시 건다 의 표 '취소' 행) |
| (소스 없음, JDK 설정·소스 grep) | `default.jfc`의 가상 스레드 이벤트 기본값과 `VirtualThreadSchedulerMXBean`/`JcmdVThreadCommands`/`VirtualThread`/`ThreadDumper` 소스 확인 | 운영 · JFR은 상시로 켜고 Virtual Thread 이벤트는 조사할 때만 켠다 |
| `VtCount.java` | 가상 스레드 2,000개 위에서 `ThreadMXBean`·`VirtualThreadSchedulerMXBean`·`jcmd` 덤프·JFR이 각각 무엇을 세는지 | 운영 · ThreadMXBean은 Virtual Thread를 세지 않는다 (재인용: 운영 · JFR은 상시로 켜고 Virtual Thread 이벤트는 조사할 때만 켠다 의 Start·End 이벤트 수, ④ 표 '관측' 행) |
| `IoVsLoom.kt` | `Thread.sleep(100)` 1,000개를 `Dispatchers.IO`/`IO.limitedParallelism(1000)`/가상 스레드 디스패처에 던졌을 때 총 시간과 플랫폼 스레드 peak | ④ Virtual Thread Dispatcher는 블로킹은 받지만 상한과 ThreadLocal은 다시 건다 |
| `BlockingWorkers.kt` (tip-p11) | 위 세 케이스에 (d) soTimeout 소켓 read (e) 읽히지 않는 stdout 파이프 println (f) VT Dispatcher 위 소켓 read 를 더해 톱 프레임 비교. 타임아웃 없는 소켓은 `SocketDispatcher.read0`, soTimeout 소켓은 `Net.poll`, stdout 막힘은 `FileOutputStream.writeBytes`, VT 는 `VirtualThread.park`(아래 `Poller.poll`) | ① 상태 열이 아니라 톱 프레임으로 판정한다 |
| `ThreadBudget.kt` (tip-p12) | Default spin 15·IO sleep 100·IO.lp(10)·IO.lp(100) 를 따로/동시에 포화. 동시 포화 1초 시점 DefaultDispatcher-worker 189 = 15+64+10+100 | ② platform thread 총량은 코어 수 + 64 + Σn까지 간다 |
| `spring/e3/PoolVsView.kt` (tip-p13) | 풀 10 앞 Coroutine 100개: IO 면 Hikari awaiting 54, IO.lp(10) 면 awaiting 0 인데 view 앞 대기 90. 두 경로 Σn 20 > 풀 10 이면 awaiting 10, 6+4 로 나누면 0. 태스크당 커넥션 2개 20건은 풀 10 에서 6~10건이 connectionTimeout(2초)으로 실패(ok/failed 14/6·12/8·10/10), 풀 11 에서 3회 모두 20/0 | ② 같은 풀을 쓰는 view는 n의 합을 풀 크기 안으로 나눈다 · ② view 앞 대기열은 Hikari 지표에 보이지 않는다 (재인용: 코드 · Dispatcher와 Executor는 프로세스에 하나씩 만들고 닫는다) |
| `spring/e4/JdbcTimeout.kt` (tip-p14) | withTimeout·runInterruptible·invokeOnCompletion·감시 Coroutine cancel·setQueryTimeout·@Transactional(timeout)·connectionTimeout 이 블로킹 JDBC(H2 TCP)를 언제 끊는지. H2 setQueryTimeout 의 세션 누수도 기록 | ③ 끊는 것은 층마다 다른 타임아웃이다 (재인용: ③ runInterruptible은 JDBC 소켓을 끊지 못한다 의 표 JDBC 행) |
| `HttpTimeout.java`, `spring/e5/RestClientTimeout.kt` (tip-p15) | HttpRequest.timeout(1s) 이 JDK 25 는 헤더까지(3초에 200), JDK 26 은 바디 소비까지(1초에 HttpTimeoutException). RestClient(JdkClientHttpRequestFactory readTimeout 1s) 는 withTimeout(100) 아래에서도 1초, runInterruptible 이면 약 100ms | ③ HttpRequest.timeout은 JDK 26부터 응답 바디까지 잰다 (재인용: ③ runInterruptible은 JDBC 소켓을 끊지 못한다 의 표 HTTP 행) |
| `TlContextVt.kt` (tip-p17) | kco/TlContext.kt 를 VT Dispatcher 로: 감싸지 않은 ThreadLocal 은 64/64 가 null(남의 값이 아니라), asContextElement 0/64 | ④ ThreadLocal은 감싸지 않으면 재개마다 null이 된다 (재인용: ④ 표 'ThreadLocal' 행) |
| `spring/e10/SpringVtSuspend.kt` (tip-p20) | spring.threads.virtual.enabled=true 여도 suspend 핸들러는 delay 뒤 `kotlinx.coroutines.DefaultExecutor`(platform). withContext(VT Dispatcher) 반환 뒤에는 VT 에 남는다. DefaultExecutor 가 2초 막히면 다른 요청의 withTimeout(100) 이 약 1.9초에 발화 | ① 진입점마다 블로킹이 남는 스레드가 다르다 (재인용: ④ ThreadLocal은 감싸지 않으면 재개마다 null이 된다 의 Observation·MDC 문단) |
| `PinBelowThreshold.java` (tip-p22) | FFM qsort upcall 안 sleep 으로 Native frame pinning. 기본 20ms 임계에서는 5ms pinning 120건이 0건, threshold=0ms 면 151~156건. Micrometer pinned Timer 도 같은 JVM 의 다른 녹화 임계를 따라간다 | 운영 · 20ms보다 짧은 pinning은 지표에 0으로 찍힌다 (재인용: ④ 표 'pinning' 행) |
| `ThreadPrintScope.java` (tip-p23) | jcmd Thread.print 가 JDK 25·26 모두 carrier 아래 'Mounted virtual thread #N' 을 찍고 unmount 된 VT 는 안 찍는다. 도움말 문구만 26 에서 바뀌었다 | 운영 · ThreadMXBean은 Virtual Thread를 세지 않는다 (Thread.print 범위) |
| `IoVsLoom.kt` VTLP10·IOLP10·VT100 (tip-p24) | VT Dispatcher.limitedParallelism(10): sleep(100)×100 이 약 1.04초, 동시 10, 100/100 이 VT 위에서 돈다. platform thread 는 carrier(ForkJoinPool) 13개 | ④ Virtual Thread Dispatcher는 블로킹은 받지만 상한과 ThreadLocal은 다시 건다 ('상한은 view로 다시 건다' 문단과 표 '64 한도와 동시 실행 상한' 행) |
| `BlockHoundVt.kt` (tip-p25) | BlockHound+kotlinx-coroutines-debug 가 Default·Default.lp(2) 의 sleep 은 잡고 IO·VT Dispatcher 는 안 잡는다(JDK 26·25) | ① BlockHound는 Default worker 위의 블로킹만 잡는다 |
| `VtGauges.java` (tip-p26) | Micrometer jvm.threads.virtual.live{mounted,queued} 가 VT 2,000개 sleep 중 0·0(합 0). CPU spin 200개 중에는 15·185 | 운영 · ThreadMXBean은 Virtual Thread를 세지 않는다 (Micrometer 게이지) |

다른 편이 이미 잰 것(3편 계획 E6·E8·E9·E11)은 여기서 다시 재지 않았다: MDCContext 스냅샷은 `kco/runs/kco-e16-mdccontext.log`, `@Transactional suspend fun` 은 `kcolab/runs/kco-e15-transactional-suspend.log`, `@Cacheable`·`@Async`·`@Timed` 와 suspend 는 `kcolab/runs/kco-e25-spring-proxy-suspend.log`·`kco-e26-timed-suspend.log`, Default 위 BlockHound 는 `kco/runs/kco-e24-blockhound.log`. PropagationContextElement 직접 사용(E11)은 시리즈 교차 조정으로 2편 몫이 되어 3편에서 재지 않았다.

3편 본문이 다시 재지 않고 수치나 결과를 인용하는 다른 편 로그는 아래 여섯이다. 본문은 이 파일들을 직접 링크하지 않고 해당 편(2편)의 절 링크와 실험 id 로 가리킨다.

| 로그 | 3편 본문이 쓰는 것 | 본문 절 |
|---|---|---|
| `../kcolab/runs/kco-e14-spring-suspend-threads.log` | Virtual Thread 를 끈 Spring suspend 핸들러의 진입·재개 스레드(tip-p20 의 끔 쪽 대조) | ① 진입점마다 블로킹이 남는 스레드가 다르다 |
| `../kco/runs/kco-e13-unconfined-blocking.log` | Spring 밖에서 잰 DefaultExecutor 타이머 지연 | ① 진입점마다 블로킹이 남는 스레드가 다르다 |
| `../kco/runs/kco-e24-blockhound.log` | Default·IO 위 BlockHound 결과(tip-p25 가 VT Dispatcher·Default view 를 더함. 설치 JVM 플래그는 2편 ① 링크로 넘김) | ① BlockHound는 Default worker 위의 블로킹만 잡는다 |
| `../kco/runs/kco-e12-io-siblings-peak.log` | 케이스마다 JVM 을 따로 띄우는 이유(worker keep-alive) | ④ Virtual Thread Dispatcher는 블로킹은 받지만 상한과 ThreadLocal은 다시 건다 |
| `../kco/runs/kco-e29-tlcontext-rerun.log` | Default 위 감싸지 않은 ThreadLocal 64/64 남의 값(tip-p17 의 대조) | ④ ThreadLocal은 감싸지 않으면 재개마다 null이 된다 |
| `../jkc/runs/e5.log` | 같은 스레드 위 `Thread.sleep(100)`×100 10,396ms 대 `delay(100)`×100 108ms | ① 남은 블로킹은 스레드 이름과 톱 프레임으로 찾는다 (번호 절 본문, 2편 링크와 함께) |

## 빌드·실행 명령

환경 변수: `J26=$(/usr/libexec/java_home -v 26)`, `J25=$(/usr/libexec/java_home -v 25)`, `KC=kotlinc 절대경로`, `CO=kotlinx-coroutines-core-jvm-1.10.2.jar 절대경로`. `java_home -v` 는 없는 버전을 상위로 폴백하니 `java -version` 으로 확인한다.

```bash
# BlockingWorkers.kt (tip-p11. stdout 은 (e) 케이스가 읽히지 않는 파이프에 쓰므로 PID 는 stderr 로 받는다. tip-p1 은 (a)~(c) 세 케이스·stdout PID 판이었다)
"$KC" -cp "$CO" BlockingWorkers.kt -include-runtime -d bw.jar
( $J26/bin/java -cp "bw.jar:$CO" BlockingWorkersKt 2> pid.txt | sleep 45 ) & sleep 5; PID=$(head -1 pid.txt)
$J26/bin/jcmd $PID Thread.dump_to_file -format=json $PWD/bw.json

# TimeoutSleep.kt
"$KC" -cp "$CO" TimeoutSleep.kt -include-runtime -d ts.jar
for i in 1 2 3; do $J26/bin/java -cp "ts.jar:$CO" TimeoutSleepKt; done

# default.jfc / JDK 소스 확인 (소스 파일 없음, grep+unzip만)
grep -n -A4 -E 'name="jdk\.VirtualThread(Start|End|Pinned|SubmitFailed)"' "$J26/lib/jfr/default.jfc"
unzip -p "$J26/lib/src.zip" jdk.management/jdk/management/VirtualThreadSchedulerMXBean.java | grep -nE '@since|^public interface|^\s+(int|long|void) [a-zA-Z]+\('

# VtCount.java
$J26/bin/java -XX:StartFlightRecording=filename=vt.jfr,settings=default,jdk.VirtualThreadStart#enabled=true,jdk.VirtualThreadEnd#enabled=true VtCount.java > vt.txt & sleep 3
PID=$(sed -n 1p vt.txt)
$J26/bin/jcmd $PID Thread.dump_to_file -format=json $PWD/vt.json; grep -c '"virtual": true' vt.json
$J26/bin/jcmd $PID help | grep -iE 'vthread'; wait; cat vt.txt
$J26/bin/jfr summary vt.jfr | grep -E 'jdk.VirtualThread(Start|End|Pinned)'

# IoVsLoom.kt (케이스마다 JVM을 새로 띄운다 — keep-alive 워커가 다음 케이스의 peak를 오염시키지 않도록)
"$KC" -cp "$CO" IoVsLoom.kt -include-runtime -d ivl.jar
for case in IO LP1000 VT; do $J26/bin/java -cp "ivl.jar:$CO" IoVsLoomKt $case; done
for case in VTLP10 IOLP10 VT100; do $J26/bin/java -cp "ivl.jar:$CO" IoVsLoomKt $case; done   # tip-p24

# 단독 Kotlin (tip-p12·p17·p25). BH·DBG 는 blockhound-1.0.17.RELEASE.jar·kotlinx-coroutines-debug-1.10.2.jar
"$KC" -cp "$CO" ThreadBudget.kt -include-runtime -d tb.jar && for c in DEF IO LP10 LP100 ALL; do $J26/bin/java -cp "tb.jar:$CO" ThreadBudgetKt $c; done
"$KC" -cp "$CO" TlContextVt.kt -include-runtime -d tlvt.jar && $J26/bin/java -cp "tlvt.jar:$CO" TlContextVtKt
"$KC" -cp "$CO:$BH" BlockHoundVt.kt -include-runtime -d bhvt.jar && $J26/bin/java -XX:+AllowRedefinitionToAddDeleteMethods -XX:+EnableDynamicAgentLoading -cp "bhvt.jar:$CO:$BH:$DBG" BlockHoundVtKt

# 단독 Java (tip-p15·p22·p23·p26). MCP = micrometer-core·commons·observation 1.17.1 + HdrHistogram 2.2.2 + micrometer-java21 1.17.1
$J25/bin/java HttpTimeout.java; $J26/bin/java HttpTimeout.java
$J26/bin/java --enable-native-access=ALL-UNNAMED -XX:StartFlightRecording=filename=pin.jfr,settings=default,jdk.VirtualThreadPinned#threshold=0ms -cp "$MCP" PinBelowThreshold.java
$J26/bin/java ThreadPrintScope.java > pid.txt & sleep 4; $J26/bin/jcmd $(head -1 pid.txt) Thread.print | grep 'Mounted virtual thread'
$J26/bin/java -cp "$MCP" VtGauges.java

# Spring·HikariCP (tip-p13·p14·p15·p20). 저장소를 rsync 한 사본에서 돌린다. main classpath 는 건드리지 않는다
./gradlew --offline -q tipLab --args='E3 1'      # 1·2·3 반복
./gradlew --offline -q tipLab --args='E4 1'      # 1·2·3 반복
./gradlew --offline -q tipLab --args='E5'
./gradlew --offline -q tipLab --args='E10 false'; ./gradlew --offline -q tipLab --args='E10 true'
```

## `runs/` 로그 색인

| 로그 | 대응 실험 |
|---|---|
| `runs/tip-p1-blocking-workers.log` | `BlockingWorkers.kt` |
| `runs/tip-p3-timeout-sleep.log` | `TimeoutSleep.kt` (3회 반복) |
| `runs/tip-p5-default-jfc.log` | `default.jfc`/JDK 소스 grep |
| `runs/tip-p7-vt-count.log` | `VtCount.java` |
| `runs/tip-p9-io-vs-loom.log` | `IoVsLoom.kt` (3회 반복) |
| `runs/tip-p11-blocking-frames.log` | `BlockingWorkers.kt` 6케이스 (3회) |
| `runs/tip-p12-thread-budget.log` | `ThreadBudget.kt` (케이스마다 새 JVM, 3회) |
| `runs/tip-p13-pool-vs-view.log` | `spring/e3/PoolVsView.kt` (케이스마다 새 JVM, 3회) |
| `runs/tip-p14-jdbc-timeout.log` | `spring/e4/JdbcTimeout.kt` (3회 + 세션 리셋 전 첫 시도) |
| `runs/tip-p15-http-timeout.log` | `HttpTimeout.java`(JDK 25·26) + `spring/e5/RestClientTimeout.kt`(JDK 26·25) (각 3회) |
| `runs/tip-p17-tl-vt-dispatcher.log` | `TlContextVt.kt` (3회) |
| `runs/tip-p20-spring-vt-suspend.log` | `spring/e10/SpringVtSuspend.kt` (VT off/on, 각 3회) |
| `runs/tip-p22-pin-below-threshold.log` | `PinBelowThreshold.java` (default / 0ms / 녹화 없음, 3회) |
| `runs/tip-p23-thread-print-scope.log` | `ThreadPrintScope.java` (JDK 25·26 각 1회) |
| `runs/tip-p24-vt-limited.log` | `IoVsLoom.kt` VTLP10·IOLP10·VT100 (케이스마다 새 JVM, 3회) |
| `runs/tip-p25-blockhound.log` | `BlockHoundVt.kt` (JDK 26·25 각 1회) |
| `runs/tip-p26-micrometer-vt-gauges.log` | `VtGauges.java` (3회) |

`git commit`/`git push`는 이 파이프라인이 하지 않는다. 파일만 놓아두고 사용자가 직접 커밋한다.
