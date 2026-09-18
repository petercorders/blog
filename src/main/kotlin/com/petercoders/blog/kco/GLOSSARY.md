# 용어 사전 — Kotlin 코루틴: 함수를 객체로 만든 결과

본문(`2-KOTLIN-COROUTINES-POST.md`)에 나온 순서대로 정리했다. 분량 상한(400줄) 때문에 본문에서 이 부록으로 옮겼다.

- **CPS(Continuation-Passing Style) 변환**: suspend 함수에 `Continuation` 파라미터를 추가하고 본문을 상태 머신으로 바꾸는 kotlinc의 컴파일 타임 변환.
- **`ContinuationImpl` / `BaseContinuationImpl`**: 이름 있는 suspend 함수의 상태 머신이 상속하는 stdlib 클래스와 그 상위 클래스. `_context`·`intercepted` 필드, `final resumeWith` 루프가 여기 있다.
- **`COROUTINE_SUSPENDED`**: suspend 함수가 "지금 멈췄다"를 호출자에게 알리는 마커 객체. 호출자도 같은 마커를 돌려주며 스택에서 빠진다.
- **`AddContinuationLowering` / `CoroutineTransformerMethodVisitor`**: 변환의 IR 단계와 바이트코드 단계. 뒤가 `tableswitch`를 조립한다.
- **`label` / `I$0` / spill**: 재개 지점 번호 필드, 서스펜션을 넘어 살아야 하는 지역변수를 필드로 올린 것(`descriptor.first() + "$" + index`), 그 올리는 작업.
- **꼬리 호출 생략**: 모든 서스펜션 포인트가 꼬리 호출인 suspend 함수는 상태 머신 클래스를 만들지 않는 최적화.
- **`invokeSuspend`**: 상태 머신 클래스가 실제로 갖는 재개 진입점. 컴파일러 소스 주석은 옛 이름 `doResume`을 아직 쓰지만 생성되는 메서드 이름은 `invokeSuspend`다.
- **`CoroutineScheduler` / `DefaultDispatcher-worker-N`**: `Dispatchers.Default`와 `IO`가 공유하는 work-stealing 풀과 그 워커 스레드 이름.
- **`controlState`**: 남은 CPU 허가(21비트)·제출된 블로킹 태스크 수(21비트)·생성 워커 수(21비트)를 담는 `Long` 필드. 21비트 × 3 = 63비트, 비트 63은 미사용.
- **CPU 허가(CPU permit)**: CPU 태스크를 실행할 권리. 개수는 `corePoolSize`. 블로킹 태스크에 들어가는 워커가 반납한다.
- **`WorkerState`**: `CPU_ACQUIRED`·`BLOCKING`·`PARKING`·`DORMANT`·`TERMINATED` 다섯 상태.
- **`executeTask(task)` / `Task.taskContext`**: 워커 상태 전이가 실제로 인라인돼 있는 메서드와, IO 태스크에 붙는 boolean 표시(`BlockingContext = true`). 1.10.2에는 `beforeTask`/`afterTask`라는 별도 메서드가 없다.
- **`UnlimitedIoScheduler` / `DefaultIoScheduler`**: 표시만 붙여 넘기는 무제한 IO 디스패처와, 그 위에 64 한도를 얹은 `Dispatchers.IO` 구현.
- **`LimitedDispatcher` / `limitedParallelism`**: 자기 큐와 `runningWorkers` 카운터로 병렬도를 제한하는 래퍼. 상태 필드는 `runningWorkers`·`queue`·`workerAllocationLock` 셋이고 스레드를 가리키는 필드는 없다.
- **elasticity**: `IO.limitedParallelism(n)`이 64에 묶이지 않고 자기 한도를 따로 갖는 성질. API 문서의 용어.
- **`BlockingEventLoop` / `ThreadLocalEventLoop` / `joinBlocking`**: `runBlocking`이 현재 스레드 위에 세우는 이벤트 루프, 그 스레드 로컬 보관소, 완료까지 `parkNanos`로 도는 루프.
- **`JobSupport` / `Finishing` / `rootCause`**: Job 상태 기계 구현, 취소·완료 중 상태 객체, 그 안의 첫 원인. `isCancelling = rootCause != null`.
- **`NodeList` / `ChildHandleNode` / `ChildContinuation`**: 부모 Job에 매달린 리스너 리스트와, 자식 Job·서스펜드된 continuation을 대표하는 노드.
- **`runInterruptible` / `ThreadState`**: Job 취소를 블로킹 스레드의 `interrupt()`로 바꿔 주는 함수와 그 상태 기계(`WORKING`→`INTERRUPTING`→`INTERRUPTED`).
- **`getFinalRootCause`**: 여러 예외 중 최종 예외를 고르는 규칙. non-CE 우선, 다음 TCE, 마지막으로 첫 CE.
- **`cancelParent` / `childCancelled` / `parentCancelled`**: 위 방향 전파의 보내는 쪽·받는 쪽, 아래 방향 전파. supervisor는 `childCancelled`만 `false`로 덮는다.
- **`CombinedContext`**: `left`/`element` 두 필드로 이어지는 `CoroutineContext`의 연결 리스트 구현(`javap` 확인, 필드 이 둘뿐). `plus`가 인터셉터를 맨 끝에 둔다.
- **`ThreadContextElement` / `asContextElement`**: 코루틴이 스레드에 올라갈 때 thread-local을 쓰고 내려올 때 복원하는 컨텍스트 요소와 그 팩토리. 다음 편에서 `TlContext.kt` 실측(순수 `ThreadLocal` 불일치 61~64/64 vs `asContextElement` 불일치 0/64)과 함께 다룬다.
