# jkc — Java 가상 스레드와 Kotlin 코루틴

블로그 글: **Java 가상 스레드와 Kotlin 코루틴: 같은 멈춤을 어느 층에서 구현하는가**
원고: `~/Desktop/blog/JAVA-KOTLIN-CONCURRENCY/JAVA-KOTLIN-CONCURRENCY-POST.md` (velog 게시 전 초안)

이 디렉터리는 그 글에 실린 모든 실험의 소스와 실행 로그를 담는다. 본문에는 요지 코드와 결정적 출력 몇 줄만 있고, 전문과 전체 로그는 여기에 있다.

## 실행 환경

| 항목 | 값 |
|---|---|
| 기기 | Apple M5 Pro (arm64) |
| 코어 수 | 15 (물리) |
| 캐시라인 | 128B (`sysctl hw.cachelinesize`) |
| OS | macOS Darwin 25.6.0 |
| JDK (가상 스레드 실험 대부분) | OpenJDK 26.0.2.1+1-7 (`/usr/libexec/java_home -v 26`) |
| JDK (`/usr/bin/java` 기본값) | Microsoft build OpenJDK 25.0.4.1 |
| JDK (pinning 프리뷰 재현, e4) | Temurin 19.0.2+7 (`--enable-preview --source 19`) |
| kotlinc | 2.3.20 (`/Applications/IntelliJ IDEA.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc`) |
| kotlinx-coroutines-core-jvm | 1.10.2 |
| 실행일 | 2026-09-09 |

**절대 수치(ms, B)는 이 기기를 탄다.** 다른 기기로 옮길 때는 절대값이 아니라 두 방식 사이의 상대적 비율만 이식한다.

## 실험 목록

| id | 실행 여부 | 무엇을 보나 | 본문 절 |
|---|---|---|---|
| e1-kotlin / e1-java | 실행 | `suspend fun`과 순차 Java 메서드를 `javap`로 대조 — `tableswitch`/`label`/`COROUTINE_SUSPENDED` 유무 | 멈춤이 구현된 층 |
| e2 | 실행 | `jcmd Thread.dump_to_file`로 가상 스레드 100개의 plain/json 덤프에서 `yieldContinuation`/`Continuation.yield0` 프레임 유무 확인 | 멈춤이 구현된 층 |
| e3-java / e3-kotlin | 실행 | 가상 스레드 캐리어 수 vs `Dispatchers.Default`/`IO` 워커 수·공유 여부 | 멈춘 것을 다시 돌리는 스케줄러 |
| e4 | 실행 | JDK 19(프리뷰)와 JDK 26(JEP 491)에서 `synchronized` pinning 유무를 시간·JFR 이벤트로 대조 | 블로킹이 새는 지점 |
| e5 | 실행 | `limitedParallelism(1)` 위에서 `Thread.sleep` vs `delay` 100회 | 블로킹이 새는 지점 |
| e6-java / e6-kotlin | 실행 | `interrupt()` vs `Job.cancel()` — 검사 있는/없는 루프, 블로킹/서스펜드 케이스 | 취소는 둘 다 협력적이다 |
| e7-java | 실행 | `StructuredTaskScope` — 자식 A 실패 시 자식 B가 끊기는 시점과 예외 모양 | 구조화된 동시성 |
| e7-kotlin | 미실행 | `coroutineScope`/`supervisorScope` 대비 버전의 e7 | 구조화된 동시성 |
| e8-kotlin | 미실행 | `ThreadLocal` vs `asContextElement()` — 디스패치 경계에서 값 유지 여부 | 컨텍스트는 어디에 실려 가는가 |
| e8-java | 미실행 | `ScopedValue` + `StructuredTaskScope.fork` 상속, `ThreadLocal` 비상속 대비 | 컨텍스트는 어디에 실려 가는가 |
| e9 | 미실행 | `Dispatchers.IO` vs `IO.limitedParallelism(1000)` vs 가상 스레드 디스패처 처리량·`peakThreadCount` | 두 세계를 잇는 디스패처 |
| e10-java / e10-kotlin | 미실행 | 100만 개 생성 시 `alloc(creator)`/`live`/`total` | 단위 하나의 값 |
| E11 (DebugProbes) | 미실행 | `kotlinx-coroutines-debug`의 코루틴 덤프 — jar가 로컬에 없음(Maven Central 다운로드 필요) | 멈춤이 구현된 층 |
| E12 (JNI pinning) | 미실행 | 네이티브 프레임 pinning — 네이티브 라이브러리 빌드(clang+JNI 헤더) 필요, 60초·java-only 제약 밖 | 블로킹이 새는 지점 |
| E13 (JEP 533/JDK 27) | 미실행 | JDK 27 로컬 미설치, JEP 원문 대조로 대체 | 구조화된 동시성 |

## 빌드·실행 명령

환경 변수(공통):

```bash
J26="$(/usr/libexec/java_home -v 26)"
J19="$(/usr/libexec/java_home -v 19)"
KC=/Applications/IntelliJ\ IDEA.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc
CO=$(find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx -name 'kotlinx-coroutines-core-jvm-1.10.2.jar' | head -1)
```

| 실험 | 명령 |
|---|---|
| e1-kotlin | `"$KC" -cp "$CO" Susp.kt -include-runtime -d susp.jar && javap -c -p -cp susp.jar SuspKt` |
| e1-java | `javac -d out Seq.java && javap -c -p -cp out Seq` |
| e2 | `$J26/bin/java VtDump.java & PID=$!; $J26/bin/jcmd $PID Thread.dump_to_file td.txt` |
| e3-java | `$J26/bin/java VtWorkers.java` / `$J26/bin/java -Djdk.virtualThreadScheduler.parallelism=4 VtWorkers.java` |
| e3-kotlin | `$J26/bin/java -cp "workers.jar:$CO" WorkersKt` |
| e4 | `$J19/bin/java --enable-preview --source 19 -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.tracePinnedThreads=short Pinning.java` / `$J26/bin/java -Djdk.virtualThreadScheduler.parallelism=1 -XX:StartFlightRecording=filename=pin26.jfr,settings=default Pinning.java` |
| e5 | `$J26/bin/java -cp "$CO" SleepVsDelayKt`(kotlinc로 컴파일 후) |
| e6-java | `$J26/bin/java InterruptLoop.java` |
| e6-kotlin | `$J26/bin/java -cp "cancel.jar:$CO" CancelLoopKt` |
| e7-java | `$J26/bin/java --enable-preview --source 26 StsFail.java` |

각 실험의 소스 전문은 본문의 `inline_snippet`이 곧 실행에 쓰인 전체 코드다(전부 ≤20줄이라 별도 파일로 분리하지 않았다).

## `runs/` 로그 색인

| 파일 | 내용 |
|---|---|
| `runs/e1-kotlin.log` | Susp.kt 컴파일·`javap` 전체 출력 + 실행 결과 |
| `runs/e1-java.log` | Seq.java `javap` 전체 출력 |
| `runs/e2.log` | `jcmd Thread.dump_to_file` plain/json 덤프 grep 결과 |
| `runs/e3-java.log` | 가상 스레드 캐리어 수 실측(기본·`parallelism=4`) |
| `runs/e3-kotlin.log` | `Dispatchers.Default`/`IO` 워커 수·공유 실측 |
| `runs/e4.log` | JDK 19/26 pinning 시간·JFR 이벤트 전체 로그(1,450줄) |
| `runs/e5.log` | `limitedParallelism(1)` 위 sleep vs delay 실행 로그 |
| `runs/e6-java.log` | `InterruptLoop` 실행 로그 |
| `runs/e6-kotlin.log` | `CancelLoop` 3회 반복 실행 로그 |
| `runs/e7-java.log` | `StructuredTaskScope` 실패 전파 실행 로그 |

git add/commit/push는 이 파이프라인이 하지 않는다. 파일만 놓아두었고, 커밋은 사용자가 직접 한다.
