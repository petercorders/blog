# jvt — Java 가상 스레드: 스택을 객체로 만든 결과

블로그 글: **Java 가상 스레드: 스택을 객체로 만든 결과**
원고: `~/Desktop/blog/JAVA-KOTLIN-CONCURRENCY/1-JAVA-VIRTUAL-THREADS-POST.md` (velog 게시 전 초안)

이 디렉터리는 그 글에 실린 실험의 실행 로그를 담는다. 이 편의 실험 소스는 전부 50줄 미만이라 본문에 인라인 스니펫으로 직접 실었고, 별도 소스 파일로는 저장하지 않았다(실행은 `/tmp` 스크래치 디렉터리에서 했다). 시리즈 2편(`jkc/`)에서 이미 만들어 둔 소스·로그 중 이 글에서 재사용한 것은 아래 표에 `jkc/`로 표시했다.

## 실행 환경

| 항목 | 값 |
|---|---|
| 기기 | Apple M5 Pro (arm64) |
| 코어 수 | 15 (물리) |
| 캐시라인 | 128B (`sysctl hw.cachelinesize`) |
| OS | macOS Darwin 25.6.0 |
| JDK 26 | OpenJDK 26.0.2.1+1-7 (`/usr/libexec/java_home -v 26`) |
| JDK 19 | Temurin 19.0.2+7 (`/usr/libexec/java_home -v 19`, `--enable-preview --source 19`) |
| `/usr/bin/java` 기본값 | Microsoft build OpenJDK 25.0.4.1 (이 편의 실험에는 안 씀) |
| 실행일 | 2026-09-10 |

**절대 수치(ms, 캐리어 수)는 이 기기를 탄다.** 다른 기기로 옮길 때는 절대값이 아니라 두 조건 사이의 상대적 비율만 이식한다.

## 실험 목록

| id | 실행 여부 | 소스 | 무엇을 보나 | 본문 절 |
|---|---|---|---|---|
| jvt-e1-javap-chain | 실행 | 인라인(`javap` 조회, 소스 파일 없음) | `parkNanos → yieldContinuation → Continuation.yield → yield0 → doYield` 바이트코드 호출 사슬, JDK19/26 `StackChunk` 필드 차이(`argsize`→`bottom`) | 스택이 객체가 되는 순간 |
| jvt-e3-dump-carrier-key | 실행 | 인라인 `VtDumpCarrier.java`(9줄) | 마운트 중(RUNNABLE)인 가상 스레드에만 `jcmd -format=json` 덤프의 `"carrier"` 키가 붙는 것 | 객체가 된 스택을 읽는 도구 |
| jvt-e4-maxpoolsize-clamps-parallelism | 실행 | `jkc/VtWorkers.java`(재사용) | `jdk.virtualThreadScheduler.maxPoolSize`가 `parallelism`을 깎는 것 — 실제 병렬도는 `min(parallelism, maxPoolSize)` | 스케줄러가 ForkJoinPool인 이유 |
| jvt-e5-vt-interrupt-latency | 실행 | 인라인 `VtInterrupt.java`(17줄) | `interrupt()`가 sleep(즉시)·폴링 루프(다음 체크)·순수 스핀(안 먹힘) 세 가상 스레드에 다르게 먹히는 것 | interrupt: 플래그 하나와 락 하나 |
| jvt-e6-two-eras-compile | 실행 | 인라인 `Jdk19Scope.java`(13줄)·`Jdk26Scope.java`(14줄) | JDK 19 인큐베이터(`ShutdownOnFailure`/`Future`) vs JDK 26 확정 프리뷰(`Joiner`/`Subtask`/`FailedException`) 바이트코드·실행 비교 | StructuredTaskScope: 7차 프리뷰까지 온 이유 |
| jvt-e7-scopedvalue-inherit | 미실행 | 인라인 `ScopedFork.java`(본문, 실행 안 함) | `ScopedValue` + `StructuredTaskScope.fork` 상속 vs `ThreadLocal` 비상속 | ScopedValue: 스택 구간이 소유하는 값 |
| (재사용) e2 | `jkc/runs/e2.log` | `jkc/`에 소스 없음(스크래치) | 가상 스레드 100개 `jcmd` 덤프의 `yieldContinuation`/`yield0` 프레임 부재 확인 | 객체가 된 스택을 읽는 도구 |
| (재사용) e3-java | `jkc/runs/e3-java.log` | `jkc/VtWorkers.java` | 기본 스케줄러의 캐리어 수 편차(`carriers=13`/`15`) | 마운트: 캐리어의 현재 스레드를 바꾸는 일 |
| (재사용) e4 | `jkc/runs/e4.log` | `jkc/Pinning.java` | JDK 19 pinning(직렬화, `tracePinnedThreads`, JFR) vs JDK 26 unmount(`jdk.VirtualThreadPinned` 0건) | Pinning: JEP 491 이후 남은 것 |
| (재사용) e6-java | `jkc/runs/e6-java.log` | `jkc/InterruptLoop.java` | 플랫폼 스레드에서 `interrupt()`가 spin/poll/sleep에 다르게 먹히는 것 | interrupt: 플래그 하나와 락 하나 |
| (재사용) e7-java | `jkc/runs/e7-java.log` | `jkc/`에 소스 없음(스크래치) | `StructuredTaskScope`에서 자식 A 실패 → 자식 B 인터럽트 → `join()` 예외 타이밍 | StructuredTaskScope: 7차 프리뷰까지 온 이유 |

## 빌드·실행 명령

환경 변수(공통):

```bash
J26="$(/usr/libexec/java_home -v 26)"
J19="$(/usr/libexec/java_home -v 19)"
```

| 실험 | 명령 |
|---|---|
| jvt-e1-javap-chain | `$J26/bin/javap -c -p java.lang.VirtualThread \| grep -nE 'void parkNanos\(long\)\|yieldContinuation\|jdk/internal/vm/Continuation\.yield\|parkOnCarrierThread'`; `$J26/bin/javap -c -p jdk.internal.vm.Continuation \| grep -nE 'static boolean yield\(\|private boolean yield0\|doYield\|enterSpecial'`; `$J26/bin/javap -p jdk.internal.vm.StackChunk`; `$J19/bin/javap -p jdk.internal.vm.StackChunk` |
| jvt-e3-dump-carrier-key | `$J26/bin/java VtDumpCarrier.java & PID=$!`; `$J26/bin/jcmd $PID Thread.dump_to_file -format=json carrier.json`; `$J26/bin/jcmd $PID Thread.dump_to_file carrier.txt` |
| jvt-e4-maxpoolsize-clamps-parallelism | `$J26/bin/java -Djdk.virtualThreadScheduler.maxPoolSize=2 VtWorkers.java`; `$J26/bin/java -Djdk.virtualThreadScheduler.parallelism=8 -Djdk.virtualThreadScheduler.maxPoolSize=3 VtWorkers.java` |
| jvt-e5-vt-interrupt-latency | `$J26/bin/java VtInterrupt.java`(x3 반복) |
| jvt-e6-two-eras-compile | `$J19/bin/javac --enable-preview --release 19 --add-modules jdk.incubator.concurrent -d out19 Jdk19Scope.java`; `$J26/bin/javac --enable-preview --release 26 -d out26 Jdk26Scope.java`; `$J19/bin/javap -c -p -cp out19 Jdk19Scope`; `$J26/bin/javap -c -p -cp out26 Jdk26Scope`; `$J19/bin/java --enable-preview --add-modules jdk.incubator.concurrent -cp out19 Jdk19Scope`; `$J26/bin/java --enable-preview -cp out26 Jdk26Scope` |
| jvt-e7-scopedvalue-inherit | 미실행. 실행하려면 `$J26/bin/java --enable-preview --source 26 ScopedFork.java`(본문 코드 참고) |

전문 소스는 본문(`1-JAVA-VIRTUAL-THREADS-POST.md`)의 해당 절에 인라인으로 있다(각 20줄 미만).

## `runs/` 로그 색인

| 파일 | 내용 |
|---|---|
| `runs/jvt-e1-javap-chain.log` | `javap` 바이트코드 조회 전체 출력 |
| `runs/jvt-e3-dump-carrier-key.log` | `VtDumpCarrier` 실행 + plain/json 덤프 전문 |
| `runs/jvt-e4-maxpoolsize-clamps-parallelism.log` | `maxPoolSize`/`parallelism` 조합별 `VtWorkers` 실행 결과 |
| `runs/jvt-e5-vt-interrupt-latency.log` | `VtInterrupt` 3회 반복 실행 결과 |
| `runs/jvt-e6-two-eras-compile.log` | JDK19/26 컴파일·`javap`·실행 전체 출력 |

시리즈 2편의 재사용 로그(`e2.log`, `e3-java.log`, `e4.log`, `e6-java.log`, `e7-java.log`)는 `../jkc/runs/`에 있다.
