# jkc — Java 가상 스레드와 Kotlin 코루틴

처음 블로그 글: **Java 가상 스레드와 Kotlin 코루틴: 같은 멈춤을 어느 층에서 구현하는가**(옛 원고 `JAVA-KOTLIN-CONCURRENCY-POST.md`, 지금은 없다)

이 디렉터리는 그 글을 위해 잰 실험의 소스와 실행 로그를 담는다. 그 글은 지금 Java·Kotlin 동시성 3부작으로 나뉘었고, 세 편이 이 디렉터리의 원 로그를 다시 재지 않고 그대로 링크한다. 각 편의 새 실험은 편마다 다른 디렉터리에 있다.

| 편 | 원고(`~/Desktop/blog/JAVA-KOTLIN-CONCURRENCY/`) | 새 실험 디렉터리 | 이 디렉터리에서 링크하는 것 |
|---|---|---|---|
| 1편 Java Virtual Thread — 켜기 전에 확인할 것 | `1-JAVA-VIRTUAL-THREADS-POST.md` | `../jvt/` | `Pinning.java`, `VtWorkers.java`, `runs/e2.log`·`e3-java.log`·`e4.log` |
| 2편 Kotlin Coroutine — suspend를 붙이기 전에 확인할 것 | `2-KOTLIN-COROUTINES-POST.md` | `../kco/`, `../kcolab/` | `Workers.kt`, `CancelLoop.kt`, `runs/e1-kotlin.log`·`e3-kotlin.log`·`e5.log`·`e6-kotlin.log` |
| 3편 실무 — 무엇이 새는지는 코드가 정한다 | `3-CONCURRENCY-PRACTICE-POST.md` | `../tip/` | 직접 링크 없음(2026-10-08 재편 후). `runs/e5.log` 의 10,396ms 대 108ms 를 '① 남은 블로킹은 스레드 이름과 톱 프레임으로 찾는다' 번호 절 본문에서 2편 링크와 함께 인용한다 |

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

'본문 절' 열은 3부작 재편 후의 절 제목이다. 옛 글의 절 이름은 쓰지 않는다. 3편 쪽 표기도 2026-10-08 재편 후 원고의 절 제목이다. 재편 후 3편은 이 디렉터리 파일을 직접 링크하지 않는다(e4 pinning 수치는 1편 링크로 넘겼고, e6-kotlin 취소 루프는 쓰지 않는다).

| id | 실행 여부 | 무엇을 보나 | 본문 절(3부작 재편 후) |
|---|---|---|---|
| e1-kotlin / e1-java | 실행 | `suspend fun`과 순차 Java 메서드를 `javap`로 대조 — `tableswitch`/`label`/`COROUTINE_SUSPENDED` 유무 | e1-kotlin: 2편 'suspend 한 단어가 실제로 바꾸는 것'. e1-java: 본문에서 쓰지 않음 |
| e2 | 실행 | `jcmd Thread.dump_to_file`로 가상 스레드 100개의 plain/json 덤프에서 `yieldContinuation`/`Continuation.yield0` 프레임 유무 확인 | 1편 '켠 다음 운영에서 보는 것' |
| e3-java / e3-kotlin | 실행 | 가상 스레드 캐리어 수 vs `Dispatchers.Default`/`IO` 워커 수·공유 여부 | e3-java: 1편 '③ back pressure를 다른 곳으로 옮겨야 한다'. e3-kotlin: 2편 '③ Dispatchers.IO의 64는 풀이 아니라 상한이다' |
| e4 | 실행 | JDK 19(프리뷰)와 JDK 26(JEP 491)에서 `synchronized` pinning 유무를 시간·JFR 이벤트로 대조 | 1편 '① synchronized pinning은 JDK 24에서 끝났다' / 'JDK 24에서 사라진 것과 남은 것'. 3편은 e4 수치를 쓰지 않고 1편 '① synchronized pinning은 JDK 24에서 끝났다'로 링크만 건다 |
| e5 | 실행 | `limitedParallelism(1)` 위에서 `Thread.sleep` vs `delay` 100회 | 2편 '① 블로킹 호출은 Dispatcher가 대신 옮겨 주지 않는다'. 3편 '① 남은 블로킹은 스레드 이름과 톱 프레임으로 찾는다'가 수치(10,396ms 대 108ms)를 2편 링크와 함께 인용 |
| e6-java / e6-kotlin | 실행 | `interrupt()` vs `Job.cancel()` — 검사 있는/없는 루프, 블로킹/서스펜드 케이스 | e6-kotlin: 2편 '취소는 suspension point에서만 끊긴다'(재편 후 3편은 쓰지 않음). e6-java: 본문에서 쓰지 않음 |
| e7-java | 실행 | `StructuredTaskScope` — 자식 A 실패 시 자식 B가 끊기는 시점과 예외 모양 | 본문에서 링크하지 않음(1편 검증 메모가 재사용 자산으로만 적는다) |
| e7-kotlin | 미실행 | `coroutineScope`/`supervisorScope` 대비 버전의 e7 | 미실행. 같은 질문은 2편 kco-e6(`../kco/ScopeFail.kt`)이 '④ 자식의 실패는 await 전에 형제를 취소한다'에서 잰다 |
| e8-kotlin | 미실행 | `ThreadLocal` vs `asContextElement()` — 디스패치 경계에서 값 유지 여부 | 미실행. 같은 질문은 2편 kco-e7·kco-e29(`../kco/TlContext.kt`)가 'ThreadLocal에 기대는 컨텍스트는 suspension을 넘지 못한다'에서 잰다 |
| e8-java | 미실행 | `ScopedValue` + `StructuredTaskScope.fork` 상속, `ThreadLocal` 비상속 대비 | 미실행. 같은 질문은 1편 jvt-e7(`../jvt/SvInherit.java`)이 'ThreadLocal 캐시는 버린다'에서 잰다 |
| e9 | 미실행 | `Dispatchers.IO` vs `IO.limitedParallelism(1000)` vs 가상 스레드 디스패처 처리량·`peakThreadCount` | 미실행. 같은 질문은 3편 tip-p9(`../tip/IoVsLoom.kt`)가 '④ Virtual Thread Dispatcher는 블로킹은 받지만 상한과 ThreadLocal은 다시 건다'에서 잰다 |
| e10-java / e10-kotlin | 미실행 | 100만 개 생성 시 `alloc(creator)`/`live`/`total` | 미실행. 본문에서 쓰지 않음 |
| E11 (DebugProbes) | 미실행 | `kotlinx-coroutines-debug`의 코루틴 덤프 — jar가 로컬에 없음(Maven Central 다운로드 필요) | 미실행. 같은 질문은 2편 kco-e23(`../kco/DebugMode.kt`)이 'Coroutine 이름과 호출 경로는 debug 모드로 본다'에서 잰다 |
| E12 (JNI pinning) | 미실행 | 네이티브 프레임 pinning — 네이티브 라이브러리 빌드(clang+JNI 헤더) 필요, 60초·java-only 제약 밖 | 미실행. Native frame pinning 자체는 3편 tip-p22(`../tip/PinBelowThreshold.java`, JNI 대신 FFM qsort upcall)가 '20ms보다 짧은 pinning은 지표에 0으로 찍힌다'에서 잰다 |
| E13 (JEP 533/JDK 27) | 미실행 | JDK 27 로컬 미설치, JEP 원문 대조로 대체 | 미실행. 본문에서 쓰지 않음 |

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
| e1-kotlin | `"$KC" -cp "$CO" Susp.kt -include-runtime -d susp.jar && javap -c -p -cp susp.jar SuspKt`(소스는 `../kco/Susp.kt`) |
| e1-java | `javac -d out Seq.java && javap -c -p -cp out Seq` |
| e2 | `$J26/bin/java VtDump.java & PID=$!; $J26/bin/jcmd $PID Thread.dump_to_file td.txt` |
| e3-java | `$J26/bin/java VtWorkers.java` / `$J26/bin/java -Djdk.virtualThreadScheduler.parallelism=4 VtWorkers.java` |
| e3-kotlin | `$J26/bin/java -cp "workers.jar:$CO" WorkersKt` |
| e4 | `$J19/bin/java --enable-preview --source 19 -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.tracePinnedThreads=short Pinning.java` / `$J26/bin/java -Djdk.virtualThreadScheduler.parallelism=1 -XX:StartFlightRecording=filename=pin26.jfr,settings=default Pinning.java` |
| e5 | `$J26/bin/java -cp "$CO" SleepVsDelayKt`(kotlinc로 컴파일 후. 소스는 `../kco/SleepVsDelay.kt`) |
| e6-java | `$J26/bin/java InterruptLoop.java` |
| e6-kotlin | `$J26/bin/java -cp "cancel.jar:$CO" CancelLoopKt` |
| e7-java | `$J26/bin/java --enable-preview --source 26 StsFail.java` |

소스 파일은 이 디렉터리의 `Workers.kt`(e3-kotlin)·`CancelLoop.kt`(e6-kotlin)·`Pinning.java`(e4)·`VtWorkers.java`(e3-java)·`InterruptLoop.java`(e6-java)다. e1-kotlin 의 `Susp.kt`와 e5 의 `SleepVsDelay.kt`는 2편 재편 때 `../kco/`에 복원했다(아래 '2026-10-08 수정'). e1-java 의 `Seq.java`, e2 의 `VtDump.java`, e7-java 의 `StsFail.java`는 저장소에 소스 파일이 없다.

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

## 2026-10-08 수정(2편 재편)

| 파일 | 바꾼 것 | 재실행 로그 |
|---|---|---|
| `Workers.kt` | 출력에 `cores=` 와 `sample:` 줄을 더해 `runs/e3-kotlin.log` 형식과 맞췄다 | `../kco/runs/kco-e10-restore-Workers.log`(원 기록과 같은 값) |
| `CancelLoop.kt` | 마지막 줄을 `poll.isCompleted=… sleep(IO).isCompleted=…` 형식으로 고치고, 끝에 `exitProcess(0)` 을 넣었다(spin 은 취소에 반응하지 않아 프로세스가 끝나지 않는다) | `../kco/runs/kco-e10-restore-CancelLoop.log`(원 기록과 같은 4줄) |

`Susp.kt`·`SleepVsDelay.kt` 는 이 디렉터리에 소스가 없었다. 로그 헤더와 본문대로 `../kco/` 에 복원했다(`kco-e10-restore-Susp.log`, `kco-e10-restore-SleepVsDelay.log`).
