# kco — Kotlin Coroutine: suspend를 붙이기 전에 확인할 것

이 디렉터리는 블로그 글 **"Kotlin Coroutine: suspend를 붙이기 전에 확인할 것"**(`~/Desktop/blog/JAVA-KOTLIN-CONCURRENCY/2-KOTLIN-COROUTINES-POST.md`, 2026-10-08 실무 기준 재편. 옛 제목 "Kotlin 코루틴: 함수를 객체로 만든 결과")의 kotlinc 단독 실험 소스와 실행 로그를 담는다. Spring 실험은 `../kcolab/`에 있다. Java·Kotlin 동시성 3부작의 2편이다. 1편(Java Virtual Thread)의 실험은 `../jvt/`에, 이 시리즈 앞선 제작(Java-Kotlin 동시성 비교 글)에서 잰 원 로그는 `../jkc/`에 있다.

## 실행 환경

| 항목 | 값 |
|---|---|
| CPU | Apple M5 Pro, arm64, 15코어 |
| OS | macOS (Darwin) |
| 캐시라인 | `hw.cachelinesize` = 128 바이트 |
| JDK | OpenJDK 26.0.2.1 (build 26.0.2.1+1-7), OpenJDK 64-Bit Server VM, mixed mode, sharing |
| Kotlin 컴파일러 | kotlinc 2.3.20 (IntelliJ IDEA 번들 `/Applications/IntelliJ IDEA.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc`) |
| kotlinx.coroutines | `kotlinx-coroutines-core-jvm` 1.10.2 (`~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/`) |
| 소스 인용 태그 | kotlinx.coroutines `1.10.2`, Kotlin 컴파일러 `v2.3.20`(일부 `v2.1.20`으로 교차 확인) |
| 실행일 | 2026-09-09 ~ 2026-09-11(kco-e1 ~ e9), 2026-10-08(kco-e10 ~ e30), 2026-10-10(kco-e31), 2026-10-11(kco-e32) — 아래 '2편 실무 재편 실험' 표 |

**절대 시간(ms)은 이 기기를 탄다.** 다른 기기에서 그대로 재현되지 않는다 — 이식 가능한 것은 비율(예: 같은 워커 위에서 `Thread.sleep`이 `delay`의 약 96배)이지 절대값이 아니다.

## 실험 목록

| 파일 | 무엇을 보나 | 본문 절 |
|---|---|---|
| `TailCall.kt` | 꼬리 호출 suspend 함수(`tail`)는 상태 머신 클래스·tableswitch를 만들지 않고, 비-꼬리 호출(`nonTail`)은 만든다 | (재편 후 본문에서 뺐다) |
| (jar 직접 javap, 소스 없음) | `CoroutineScheduler`의 `controlState` 비트 상수(`BLOCKING_SHIFT`, `CPU_PERMITS_SHIFT`, `MAX_SUPPORTED_POOL_SIZE`)와 `WorkerState` enum 5종이 실제로 그 이름·값으로 컴파일돼 있는지 | (재편 후 본문에서 뺐다) |
| `IoSiblings.kt` | `Dispatchers.IO`와 `IO.limitedParallelism(n)`이 같은 스레드 풀을 공유하되 각자 다른 병렬도 한도를 갖는 "형제" 관계임을 실측 | IO.limitedParallelism(n)은 64를 나눠 쓰지 않는다 (peak 해석은 kco-e12) |
| `IoWorkerCount.kt` | (kco-e31) 케이스마다 별도 JVM 에서 이름이 `DefaultDispatcher-worker-` 인 스레드만 세어, IO 가 64개를 넘겨 worker 를 만드는지와 태스크를 받지 않은 여분 수를 본다 | ③ Dispatchers.IO의 64는 풀이 아니라 상한이다 |
| `NestedRunBlocking.kt` | (kco-e4, 옛 판) `limitedParallelism(1)` 워커 위에서 중첩 `runBlocking { delay(300) }`이 다른 코루틴을 굶기는지. 대조군 d 도 311ms 를 기다려 결론을 가르지 못하는 설계였다 → kco-e11 에서 다시 짰다 | suspend 안의 runBlocking은 delay를 블로킹으로 바꾼다 (v2 수치만 쓴다) |
| `RintCause.kt` | `runInterruptible` 취소 시 `cause`가 `null`(Job.cancel() 경로)인지 `InterruptedException`(스레드 직접 interrupt 경로)인지가 취소의 출처에 따라 갈림 | 취소는 suspension point에서만 끊긴다 |
| `ScopeFail.kt` | `coroutineScope`는 자식 실패 시 형제를 즉시 취소하지만 `supervisorScope`는 그렇지 않음(`async` 예외가 `await()` 전에 이미 형제를 취소하는지 여부) | ④ 자식의 실패는 await 전에 형제를 취소한다 / supervisorScope는 서로 독립인 작업에만 쓴다 |
| `TlContext.kt` | 감싸지 않은 `ThreadLocal`이 코루틴 재개 스레드가 바뀔 때 깨지고(n=64, 3회 반복 불일치 64·62·63/64 — 로그 `kco-e7-tlcontext.log` 기준. 이전 판의 '61~64' 는 틀린 값), `asContextElement`로 감싸면 유지되는지(3회 모두 불일치 0/64) | ThreadLocal에 기대는 컨텍스트는 suspension을 넘지 못한다 (재현 확인은 kco-e29) |
| (jar 직접 javap) | `kotlin.coroutines.CombinedContext`가 `left`/`element` 두 필드로만 이루어진 연결 리스트인지(확인됨) | (본문에서 쓰지 않음) |

`Susp.kt`('suspend 한 단어가 실제로 바꾸는 것'), `SleepVsDelay.kt`('① 블로킹 호출은 Dispatcher가 대신 옮겨 주지 않는다'), `Workers.kt`('③ Dispatchers.IO의 64는 풀이 아니라 상한이다'), `CancelLoop.kt`('취소는 suspension point에서만 끊긴다')의 원 기록 로그는 이 시리즈 앞선 제작(`jkc/`) 때 잰 `../jkc/runs/`의 `e1-kotlin`·`e5`·`e3-kotlin`·`e6-kotlin`이다. 2026-10-08 재편에서 `Susp.kt`·`SleepVsDelay.kt`·`RintCause.kt`·`ScopeFail.kt`를 이 디렉터리에 복원했고 `../jkc/Workers.kt`·`../jkc/CancelLoop.kt`는 로그 형식에 맞게 고쳐 다시 돌렸다(아래 kco-e10-restore 표).

## 2회차 감사 반영(이번 라운드)

- `javap -p -constants CoroutineScheduler`로 `CPU_PERMITS_MASK=9223367638808264704`를 직접 확인 — `controlState` 비트 폭이 21/21/21(비트 63 미사용)임을 확정. 본문이 22/21/21로 잘못 썼던 것을 정정.
- `javap -p 'CoroutineScheduler$Worker'`로 `beforeTask`/`afterTask`가 1.10.2에 존재하지 않고 로직이 `executeTask(task)`에 인라인돼 있음을 확인(`Task.taskContext: Boolean`, `BlockingContext=true`).
- `kco-e7-tlcontext`(TlContext.kt, n=64, 3회)를 실행 — 순수 `ThreadLocal` 불일치 64·62·63/64(이전 판의 '61~64'는 틀린 값), `asContextElement` 불일치 0/64. `kco-e8`은 별도 로그가 아니라 이 로그 뒷부분(CombinedContext javap)이다.
- `kco-e8-javap-combinedcontext`(`javap -p kotlin.coroutines.CombinedContext`)를 실행 — 필드 `left`·`element` 둘뿐.
- `javap -p 'SuspKt$work$1'`로 생성 메서드가 `doResume`이 아니라 `invokeSuspend`임을 확인.
- `javap -p LimitedDispatcher`로 필드 7개(`$$delegate_0`, `dispatcher`, `parallelism`, `name`, `runningWorkers$volatile`, `queue`, `workerAllocationLock`)를 확인 — 본문 "필드 셋이다"를 정정.
- "컨텍스트는 스레드가 아니라 객체에 붙는다" 절은 분량 상한(본문 400줄) 때문에 본 편에서 통째로 뺐다. (2026-10-08 재편에서 `TlContext.kt` 실측은 코드 절 'ThreadLocal에 기대는 컨텍스트는 suspension을 넘지 못한다'로 돌아왔다.)
- 용어 사전은 분량 때문에 본문에서 이 디렉터리의 `GLOSSARY.md`로 옮겼다. (2026-10-08 재편에서 본문의 용어 사전 절과 링크를 없앴다. `GLOSSARY.md`는 낡은 내용이라 삭제 후보다.)

## 빌드·실행 명령

```bash
KC="/Applications/IntelliJ IDEA.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc"
CO=$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/*/kotlinx-coroutines-core-jvm-1.10.2.jar)

# kco-e1-tailcall
"$KC" -cp "$CO" TailCall.kt -include-runtime -d tc.jar
unzip -l tc.jar | grep -E 'TailCallKt\$'
javap -c -p -cp tc.jar TailCallKt | grep -cE 'tableswitch'
javap -c -p -cp tc.jar TailCallKt
java -cp "tc.jar:$CO" TailCallKt

# kco-e2-javap-scheduler (소스 없음, jar 직접 역어셈블)
javap -p -constants -cp "$CO" kotlinx.coroutines.scheduling.CoroutineScheduler \
  | grep -E 'controlState|BLOCKING_SHIFT|CREATED_MASK|BLOCKING_MASK|CPU_PERMITS_SHIFT|CPU_PERMITS_MASK|MAX_SUPPORTED_POOL_SIZE|blockingTasks|incrementBlockingTasks'
javap -p -cp "$CO" 'kotlinx.coroutines.scheduling.CoroutineScheduler$WorkerState' \
  | grep -E 'CPU_ACQUIRED|BLOCKING|PARKING|DORMANT|TERMINATED'

# kco-e3-io-siblings
"$KC" -cp "$CO" IoSiblings.kt -include-runtime -d ios.jar && java -cp "ios.jar:$CO" IoSiblingsKt

# kco-e4-nested-runblocking
"$KC" -cp "$CO" NestedRunBlocking.kt -include-runtime -d nrb.jar && java -cp "nrb.jar:$CO" NestedRunBlockingKt

# kco-e5-rint-cause
"$KC" -cp "$CO" RintCause.kt -include-runtime -d rc.jar && java -cp "rc.jar:$CO" RintCauseKt

# kco-e6-scopefail
"$KC" -cp "$CO" ScopeFail.kt -include-runtime -d sf.jar && java -cp "sf.jar:$CO" ScopeFailKt

# kco-e7-tlcontext
"$KC" -cp "$CO" TlContext.kt -include-runtime -d tl.jar && java -cp "tl.jar:$CO" TlContextKt

# kco-e8-javap-combinedcontext
KLIB="/Applications/IntelliJ IDEA.app/Contents/plugins/Kotlin/kotlinc/lib"
javap -p -cp "$KLIB/kotlin-stdlib.jar" kotlin.coroutines.CombinedContext

# kco-e9-invokesuspend (Susp.kt 재사용, 2회차 감사에서 확인)
javap -p -cp susp.jar 'SuspKt$work$1' | grep -iE 'invokeSuspend|doResume|resumeWith'

# 2회차 감사 — beforeTask/afterTask 실재 여부, executeTask 인라인 확인
javap -p -cp "$CO" 'kotlinx.coroutines.scheduling.CoroutineScheduler$Worker' | grep -iE 'beforetask|aftertask|executeTask|findTask|findBlockingTask|findCpuTask'
javap -p -cp "$CO" kotlinx.coroutines.scheduling.Task
javap -p -constants -cp "$CO" kotlinx.coroutines.scheduling.TasksKt | grep -iE 'blocking'

# 2회차 감사 — LimitedDispatcher 필드 개수 확인
javap -p -cp "$CO" kotlinx.coroutines.internal.LimitedDispatcher
```

`ScopeFail.kt`는 원본 `println(a.await() + b.await())`가 오버로드 해석 모호성 컴파일 에러를 내(`a`의 `async` 블록이 항상 `throw`해 반환 타입이 `Nothing`으로 추론되는 것이 원인) `println("" + a.await() + b.await())`로 최소 수정했다.

## `runs/` 로그 색인

| 로그 파일 | 실험 |
|---|---|
| `runs/kco-e1-tailcall.log` | TailCall.kt — unzip 목록, tableswitch 개수, javap 디스어셈블, 실행 결과 |
| `runs/kco-e2-javap-scheduler.log` | CoroutineScheduler·WorkerState javap 결과. 2회차 감사에서 controlState 상수, executeTask/Task/TasksKt/LimitedDispatcher javap 를 덧붙였다 |
| `runs/kco-e3-io-siblings.log` | IoSiblings.kt 실행 결과(디스패처별 소요 시간·스레드 수) |
| `runs/kco-e4-nested-runblocking.log` | NestedRunBlocking.kt 실행 결과(1회분만 있다. 이전 판의 '2회'는 틀린 기록) |
| `runs/kco-e5-rint-cause.log` | RintCause.kt 실행 결과(cancel() vs interrupt() 대조) |
| `runs/kco-e6-scopefail.log` | ScopeFail.kt 실행 결과(coroutineScope vs supervisorScope) |
| `runs/kco-e7-tlcontext.log` | TlContext.kt 실행 결과(3회) + CombinedContext javap |
| `runs/kco-e9-invokesuspend.log` | SuspKt$work$1의 invokeSuspend 확인 |

kco-e10 ~ kco-e32 로그는 아래 '2편 실무 재편 실험' 표에 있다.

## 참고

- 실험 소스는 이 폴더, 실행 로그는 `runs/`에 있다. Spring 실험은 `../kcolab/`, 재사용한 원 로그는 `../jkc/runs/`에 있다.
- 본문 링크 베이스: `https://github.com/petercorders/blog/blob/main/src/main/kotlin/com/petercoders/blog/kco/`

## 2편 실무 재편 실험(kco-e10 ~ kco-e32, 2026-10-08 · kco-e31 은 2026-10-10 · kco-e32 는 2026-10-11 최종 감사 반영)

2편을 실무 기준(두괄식, ①~⑤ 절)으로 재편하면서 새로 돌린 실험이다. 원 로그(e5, e6-kotlin, e3-kotlin, kco-e3~e7)는 **기록값**으로 두고, `kco-e10-restore-*` 는 복원한 소스로 다시 돌린 **재현 확인**이다. 모든 새 로그 머리에는 `java -version`·kotlinc 버전·의존성·실행 시각·부하(load average)·명령이 있다. 실행일에 다른 글의 실험이 같은 기기에서 동시에 돌아 load average 가 7~55 사이를 오갔다. 절대 ms 는 그 부하를 탄다.

kotlinc 단독 실험 환경: OpenJDK 26.0.2.1, kotlinc 2.3.20(IntelliJ 번들), kotlinx-coroutines-core-jvm 1.10.2. 1.11.0 jar 는 `launch(Job)` 컴파일 경고(@Deprecated WARNING, #4435) 비교(kco-e19)에만 쓴다. Spring 실험(kco-e14·e15·e18·e25·e26)은 `../kcolab/` 에 있다(Gradle sourceSet `kcolab`, toolchain JDK 25).

| 로그(`runs/`) | 소스 | 무엇을 보나 | 결과 요지 | 본문 절(재편 후) |
|---|---|---|---|---|
| `kco-e10-restore-Susp.log` | `Susp.kt` | 본문 요지 코드 복원·컴파일, `SuspKt$work$1` 필드 | `int I$0; Object result; int label;` + `invokeSuspend`, 출력 5 | suspend 한 단어가 실제로 바꾸는 것 |
| `kco-e10-restore-SleepVsDelay.log` | `SleepVsDelay.kt` | e5 재현 | 10,709/117 · 16,899/107 · 10,648/114 ms(부하 탐, 원 기록 10,396/108) | ① 블로킹 호출은 Dispatcher가 대신 옮겨 주지 않는다 |
| `kco-e10-restore-RintCause.log` | `RintCause.kt` | kco-e5 재현 | cancel() → cause=null, 직접 interrupt() → cause=InterruptedException (원 기록과 같음) | 취소는 suspension point에서만 끊긴다 |
| `kco-e10-restore-ScopeFail.log` | `ScopeFail.kt` | kco-e6 재현(`"" + a.await()` 수정본) | B cancelled 82 ms / scope threw 83 ms (원 기록 73), supervisor 두 줄 같음 | ④ 자식의 실패는 await 전에 형제를 취소한다 |
| `kco-e10-restore-Workers.log` | `../jkc/Workers.kt`(로그 형식으로 수정) | e3-kotlin 재현 | Default 15 / IO 64 / shared 15, core.pool.size=4 → 4 (같음) | ③ Dispatchers.IO의 64는 풀이 아니라 상한이다 |
| `kco-e10-restore-CancelLoop.log` | `../jkc/CancelLoop.kt`(로그 형식으로 수정, 끝에 exitProcess) | e6-kotlin 재현 | 4줄 원 기록과 같음 | 취소는 suspension point에서만 끊긴다 |
| `kco-e11-nested-runblocking-v2.log` | `NestedRunBlocking.kt`(v2) | limitedParallelism(1) 위 중첩 runBlocking 뒤 형제 vs 순수 delay 뒤 형제, bol.com 2스레드 풀 V1/V2/V3 | A 301~313 ms vs B 0 ms(9회), V1 1010~1014 ms, V2 2011~2012 ms(첫 실행 8,516 ms 는 부하 이상치), V3 교착(jcmd: main·pool 두 스레드 모두 `BlockingCoroutine.joinBlocking`) | suspend 안의 runBlocking은 delay를 블로킹으로 바꾼다 |
| `kco-e12-io-siblings-peak.log` | `IoSiblings.kt`(케이스 인자, reset+live) | peakThreads 264 의 원인 | 한 JVM: LP16 시작 live=264(앞 케이스 worker 생존) → keep-alive. 별도 JVM LP16 peak 25~27. IO peak 74~76(live 수에는 worker 아닌 스레드도 섞일 수 있어 64 초과 판정은 kco-e31 로 한다) | IO.limitedParallelism(n)은 64를 나눠 쓰지 않는다 · 3편 '④ Virtual Thread Dispatcher는 블로킹은 받지만 상한과 ThreadLocal은 다시 건다'(케이스마다 JVM 을 따로 띄우는 이유) |
| `kco-e13-unconfined-blocking.log` | `UnconfinedBlocking.kt` | Unconfined 에서 delay(1) 뒤 sleep(100) ×20, 타이머 프로브 | 재개 스레드 전부 `kotlinx.coroutines.DefaultExecutor`, 총 2,774~2,879 ms vs withContext(IO) 113~116 ms. 그 동안 Default 위 delay(10) 최대 지연 2,096~2,146 ms(평소 5~7) | ① 블로킹 호출은 Dispatcher가 대신 옮겨 주지 않는다 · 3편 '① 진입점마다 블로킹이 남는 스레드가 다르다'(Spring 밖 타이머 지연) |
| `kco-e16-mdccontext.log` | `MdcContext.kt` | MDCContext 생성 시점, Coroutine 안 MDC.put | put 뒤 생성 → 값 유지, put 앞 생성 → null, 안에서 put → delay 뒤 null, MDCContext 없이 63·61·64/64 불일치, put 뒤 per-coroutine 생성 0/64 (logback; slf4j-simple 은 NOPMDCAdapter 라 무효) | ThreadLocal에 기대는 컨텍스트는 suspension을 넘지 못한다 |
| `kco-e17-not-a-mutex.log` | `NotAMutex.kt` | limitedParallelism(4) vs Semaphore(4), JUC vs kotlinx Semaphore | 피크 100(105~109 ms) vs 4(2,653~2,704 ms). JUC acquire 두 개가 병렬도 2 view 의 셋째를 521~534 ms 까지 막음, kotlinx 는 29~30 ms | suspend 호출과 fan-out에는 Semaphore로 상한을 건다 |
| `kco-e19-scope-link.log` | `ScopeLink.kt` | CEH 위치, launch(SupervisorJob()), 1.11.0 경고 | 루트 CEH 호출, 자식 CEH 무시(uncaught handler 로), supervisorScope 직속 CEH 호출, async CEH 무시. scope.cancel() 뒤 detached isActive=true. 1.11.0 컴파일 경고 문구 기록, 1.10.2 경고 없음 | CoroutineExceptionHandler는 루트에서만 받는다 / launch(SupervisorJob())은 scope와의 연결을 끊는다 |
| `kco-e20-getter-scope.log` | `GetterScope.kt` | getter SupervisorJob scope, runBlocking{CoroutineScope(IO).launch} | getter: cancel 뒤 [true,true,true], 필드: [false,false,false]. runBlocking 4 ms 에 반환, done 506~514 ms | ⑤ 직접 만든 CoroutineScope는 직접 닫아야 한다 / launch(SupervisorJob())은 scope와의 연결을 끊는다 |
| `kco-e21-swallow-cancel.log` | `SwallowCancel.kt` | catch(Exception)/runCatching 재시도 루프 | 취소 뒤 남은 반복 4번이 즉시(같은 ms) 돌고 loop done, ensureActive 처방은 즉시 종료 | CancellationException을 삼키지 않는다 |
| `kco-e22-heap-histogram.log` | `HeapCoroutines.kt` | 10만 개 delay(MAX) | histogram: CancellableContinuationImpl·DispatchedContinuation 100,001, `HeapCoroutinesKt$main$1$1$1` 100,000, StandaloneCoroutine 100,000. 덤프: worker 15개, 람다 프레임 0 | 스레드 덤프 대신 Heap histogram으로 센다 |
| `kco-e23-debug-probes.log` | `DebugMode.kt` | debug 스레드 이름, stack trace recovery, DebugProbes | `@coroutine#2`·`@excel#3`, recovery 켜면 handle()/main 프레임 복원. DebugProbes 는 JNA 없으면 'No compatible attachment provider', JNA 5.9.0 더하면 JDK 26 에서 동작, -javaagent 도 동작 | Coroutine 이름과 호출 경로는 debug 모드로 본다 |
| `kco-e24-blockhound.log` | `BlockHoundCheck.kt` | BlockHound 1.0.17 + coroutines 통합 | JDK 26·25 모두 Default 위 sleep → `BlockingOperationError: Blocking call! java.lang.Thread.sleepNanos0`, IO 는 통과. `-XX:+AllowRedefinitionToAddDeleteMethods` 필요 | ① 블로킹 호출은 Dispatcher가 대신 옮겨 주지 않는다 · 3편 '① BlockHound는 Default worker 위의 블로킹만 잡는다'(tip-p25 가 VT Dispatcher·Default view 를 더함) |
| `kco-e27-sync-suspend.log` | `SyncSuspend.kt`(컴파일 실패가 정상) | synchronized 안 suspension | `error: the 'delay' suspension point is inside a critical section.` | synchronized와 ReentrantLock 대신 Mutex를 쓴다 |
| `kco-e28-reentrantlock-suspend.log` | `ReentrantLockSuspend.kt` | ReentrantLock 을 suspension 너머로 | 10회 모두 IMSE 1/20, 임계 구역 동시 19개(같은 스레드 재진입), 끝에 락이 잠긴 채 남음. Mutex 는 동시 1·오류 0 | synchronized와 ReentrantLock 대신 Mutex를 쓴다 |
| `kco-e29-tlcontext-rerun.log` | `TlContext.kt` | kco-e7 재현(헤더 포함) | 64·64·64/64, asContextElement 0/64 | ThreadLocal에 기대는 컨텍스트는 suspension을 넘지 못한다 · 3편 '④ ThreadLocal은 감싸지 않으면 재개마다 null이 된다'(Default 쪽 대조) |
| `kco-e30-withlock-suspend.log` | `WithLockSuspend.kt`(컴파일 실패가 정상), `ExplicitLockSuspend.kt` | `kotlin.concurrent.withLock` 안 suspension, lock()/unlock() 직접 호출 | withLock 블록은 kco-e27 과 같은 `error: the 'delay' suspension point is inside a critical section.`(K2 `FirJvmSuspensionPointInsideMutexLockChecker`), lock()/unlock() 직접 호출은 컴파일·실행됨 | synchronized와 ReentrantLock 대신 Mutex를 쓴다 |
| `kco-e31-io-worker-count.log` | `IoWorkerCount.kt` | kco-e12 의 live 수로는 IO 의 64 초과 여부를 가를 수 없다는 감사 지적(2026-10-10). 이름이 `DefaultDispatcher-worker-` 인 스레드만 센다(1ms 샘플 peak, 종료 뒤 생존 수, 최대 번호), 새로 생긴 worker 아닌 스레드 이름도 찍는다. 케이스마다 별도 JVM, 각 3회 | IO: worker 67(3회 같음), 태스크 실행 64. LP256: 259~264 중 256. LP16: 19~20 중 16. 새 non-worker 스레드 0. 여분은 1.10.2 `CoroutineScheduler.tryCreateWorker`(non-blocking 작업용 worker 확보)가 만든다. 여분 수는 조건식의 상한(코어 수 15)이 아니라 dispatch 타이밍이 정한다. 블로킹 dispatch 는 park 된 worker 를 먼저 깨우고, 없을 때만 하나(created−blocking 이 0이던 순간에만 둘)를 만든다. LP256 여분 3~8 이 실행마다 달랐다 | ③ Dispatchers.IO의 64는 풀이 아니라 상한이다 · IO.limitedParallelism(n)은 64를 나눠 쓰지 않는다(kco-e12 peak 해석) |
| `kco-e32-invokesuspend-disasm.log` | `Susp.kt`(kco-e9 와 같은 소스, 재컴파일) | 최종 감사 지적(2026-10-11): `invokeSuspend`가 label로 재개 지점을 고른다는 서술을 kco-e9(시그니처 grep)·kco-e10-restore-Susp(SuspKt 전체 tableswitch 개수)가 받쳐 주지 않았다. `javap -c -p 'SuspKt$work$1'`와 `javap -c -p SuspKt` 전체 디스어셈블 | `invokeSuspend(Object)` 는 result 필드 저장 → label에 `Int.MIN_VALUE` ior → `invokestatic SuspKt.work(Continuation)` → areturn 네 줄이 전부, tableswitch 없음. label 0~2 tableswitch 는 `SuspKt.work(Continuation)` 안(65번째 바이트)에 있다 | ③ 실행되는 것은 함수가 아니라 이 객체다(첫 절), 그림 02 |

Spring 실험(kco-e14·e15·e18·e25·e26)의 표는 `../kcolab/README.md` 에 있다.

3편(`3-CONCURRENCY-PRACTICE-POST.md`, 2026-10-08 재편)은 위 표의 kco-e12·e13·e24·e29 와 `../kcolab/` 의 kco-e14('① 진입점마다 블로킹이 남는 스레드가 다르다', Virtual Thread 를 끈 쪽 대조)를 다시 재지 않고 2편 링크와 실험 id 로 인용한다. 이 디렉터리 파일을 직접 링크하지는 않는다. 3편이 새로 잰 실험은 `../tip/README.md` 에 있다.

### 빌드·실행 명령(kco-e10 ~ e32)

```bash
KC="/Applications/IntelliJ IDEA.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc"
J26=~/Library/Java/JavaVirtualMachines/openjdk-26.0.2.1/Contents/Home
CO=$(ls ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/*/kotlinx-coroutines-core-jvm-1.10.2.jar)
# 공통: "$KC" -cp "$CO[:추가 jar]" X.kt -include-runtime -d X.jar && $J26/bin/java -cp "X.jar:$CO[:추가 jar]" XKt [args]
# 추가 jar(Maven Central): kotlinx-coroutines-slf4j 1.10.2 + slf4j-api 2.0.18 + logback-classic/core 1.5.38 (kco-e16)
#                          kotlinx-coroutines-debug 1.10.2 + byte-buddy/-agent 1.10.9 또는 1.18.11 + jna/jna-platform 5.9.0 (kco-e23)
#                          blockhound 1.0.17.RELEASE + kotlinx-coroutines-debug 1.10.2, JVM 옵션 -XX:+AllowRedefinitionToAddDeleteMethods (kco-e24)
#                          kotlinx-coroutines-core-jvm 1.11.0 (kco-e19 경고 비교, 컴파일만)
# 인자: NestedRunBlockingKt [deadlock] · IoSiblingsKt {ALL|IO|LP256|LP16} · IoWorkerCountKt {IO|LP256|LP16}(케이스마다 별도 JVM) · HeapCoroutinesKt 100000 · DebugModeKt {names|trace|probes}
# kco-e32: "$KC" -cp "$CO" Susp.kt -include-runtime -d susp.jar && $J26/bin/javap -c -p -cp susp.jar 'SuspKt$work$1' && $J26/bin/javap -c -p -cp susp.jar SuspKt
```
