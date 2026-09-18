# kco — Kotlin 코루틴: 함수를 객체로 만든 결과

이 디렉터리는 블로그 글 **"Kotlin 코루틴: 함수를 객체로 만든 결과"**(`~/Desktop/blog/JAVA-KOTLIN-CONCURRENCY/2-KOTLIN-COROUTINES-POST.md`)의 실험 소스와 실행 로그를 담는다. 1편(`jkc/`, Java-Kotlin 동시성 비교)에서 이어지는 시리즈의 2편이다.

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
| 실행일 | 2026-09-09 ~ 2026-09-11 |

**절대 시간(ms)은 이 기기를 탄다.** 다른 기기에서 그대로 재현되지 않는다 — 이식 가능한 것은 비율(예: 같은 워커 위에서 `Thread.sleep`이 `delay`의 약 96배)이지 절대값이 아니다.

## 실험 목록

| 파일 | 무엇을 보나 | 본문 절 |
|---|---|---|
| `TailCall.kt` | 꼬리 호출 suspend 함수(`tail`)는 상태 머신 클래스·tableswitch를 만들지 않고, 비-꼬리 호출(`nonTail`)은 만든다 | 멈춤을 컴파일러가 만든다 |
| (jar 직접 javap, 소스 없음) | `CoroutineScheduler`의 `controlState` 비트 상수(`BLOCKING_SHIFT`, `CPU_PERMITS_SHIFT`, `MAX_SUPPORTED_POOL_SIZE`)와 `WorkerState` enum 5종이 실제로 그 이름·값으로 컴파일돼 있는지 | 그 객체를 돌리는 스케줄러 |
| `IoSiblings.kt` | `Dispatchers.IO`와 `IO.limitedParallelism(n)`이 같은 스레드 풀을 공유하되 각자 다른 병렬도 한도를 갖는 "형제" 관계임을 실측 | Dispatchers.IO는 별도 풀이 아니다 |
| `NestedRunBlocking.kt` | `limitedParallelism(1)` 워커 위에서 중첩 `runBlocking { delay(300) }`이 다른 코루틴을 순수 블로킹처럼 굶긴다는 것 | 블로킹이 새는 지점 |
| `RintCause.kt` | `runInterruptible` 취소 시 `cause`가 `null`(Job.cancel() 경로)인지 `InterruptedException`(스레드 직접 interrupt 경로)인지가 취소의 출처에 따라 갈림 | 취소는 왜 Job 트리인가 |
| `ScopeFail.kt` | `coroutineScope`는 자식 실패 시 형제를 즉시 취소하지만 `supervisorScope`는 그렇지 않음(`async` 예외가 `await()` 전에 이미 형제를 취소하는지 여부) | coroutineScope와 supervisorScope, 예외가 가는 방향 |
| `TlContext.kt` | 감싸지 않은 `ThreadLocal`이 코루틴 재개 스레드가 바뀔 때 깨지고(n=64, 3회 반복 불일치 61~64/64), `asContextElement`로 감싸면 유지되는지(3회 모두 불일치 0/64) — **다음 편**(컨텍스트/ThreadLocal)용으로 실행만 해 두었다, 본 편 본문에는 없음 |
| (jar 직접 javap) | `kotlin.coroutines.CombinedContext`가 `left`/`element` 두 필드로만 이루어진 연결 리스트인지(확인됨) — 다음 편용 |

`Susp.kt`(1절 상태 머신 예제), `Workers.kt`(2절 워커 수), `SleepVsDelay.kt`(4절 sleep vs delay), `CancelLoop.kt`(5절 취소 5대상)는 1편(`jkc/`) 제작 과정에서 이미 실행·검증한 것을 그대로 재사용했다. 해당 소스·로그는 `../jkc/`와 `../jkc/runs/`에 있다.

## 2회차 감사 반영(이번 라운드)

- `javap -p -constants CoroutineScheduler`로 `CPU_PERMITS_MASK=9223367638808264704`를 직접 확인 — `controlState` 비트 폭이 21/21/21(비트 63 미사용)임을 확정. 본문이 22/21/21로 잘못 썼던 것을 정정.
- `javap -p 'CoroutineScheduler$Worker'`로 `beforeTask`/`afterTask`가 1.10.2에 존재하지 않고 로직이 `executeTask(task)`에 인라인돼 있음을 확인(`Task.taskContext: Boolean`, `BlockingContext=true`).
- `kco-e7-tlcontext`(TlContext.kt, n=64, 3회)를 실행 — 순수 `ThreadLocal` 불일치 61~64/64, `asContextElement` 불일치 0/64.
- `kco-e8-javap-combinedcontext`(`javap -p kotlin.coroutines.CombinedContext`)를 실행 — 필드 `left`·`element` 둘뿐.
- `javap -p 'SuspKt$work$1'`로 생성 메서드가 `doResume`이 아니라 `invokeSuspend`임을 확인.
- `javap -p LimitedDispatcher`로 필드 7개(`$$delegate_0`, `dispatcher`, `parallelism`, `name`, `runningWorkers$volatile`, `queue`, `workerAllocationLock`)를 확인 — 본문 "필드 셋이다"를 정정.
- "컨텍스트는 스레드가 아니라 객체에 붙는다" 절은 분량 상한(본문 400줄) 때문에 본 편에서 통째로 뺐다. `TlContext.kt`와 그 실측은 다음 편에서 쓴다.
- 용어 사전은 분량 때문에 본문에서 이 디렉터리의 `GLOSSARY.md`로 옮겼다.

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
| `runs/kco-e2-javap-scheduler.log` | CoroutineScheduler·WorkerState javap 결과 |
| `runs/kco-e3-io-siblings.log` | IoSiblings.kt 실행 결과(디스패처별 소요 시간·스레드 수) |
| `runs/kco-e4-nested-runblocking.log` | NestedRunBlocking.kt 실행 결과(2회) |
| `runs/kco-e5-rint-cause.log` | RintCause.kt 실행 결과(cancel() vs interrupt() 대조) |
| `runs/kco-e6-scopefail.log` | ScopeFail.kt 실행 결과(coroutineScope vs supervisorScope) |
| `runs/kco-e7-tlcontext.log` | TlContext.kt 실행 결과(3회) + CombinedContext javap |
| `runs/kco-e2-javap-scheduler.log` | (2회차 갱신) controlState 상수, WorkerState enum, executeTask/Task/TasksKt/LimitedDispatcher javap |
| `runs/kco-e9-invokesuspend.log` | SuspKt$work$1의 invokeSuspend 확인 |

## 참고

- 이 저장소에는 `git add`/`git commit`/`git push`를 하지 않았다. 커밋·푸시는 사용자가 직접 한다.
- 본문 링크 베이스: `https://github.com/petercorders/blog/blob/main/src/main/kotlin/com/petercoders/blog/kco/`
