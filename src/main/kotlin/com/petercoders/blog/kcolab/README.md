# kcolab — Kotlin Coroutine 2편 Spring 실험

블로그 글 **"Kotlin Coroutine: suspend를 붙이기 전에 확인할 것"**(`~/Desktop/blog/JAVA-KOTLIN-CONCURRENCY/2-KOTLIN-COROUTINES-POST.md`)의 Spring 실험이다. kotlinc 단독 실험은 `../kco/` 에 있다.

## 왜 별도 sourceSet 인가

`kco/`·`jkc/`·`tip/` 은 `sourceSets.main` 에서 빠져 있어 Gradle 이 컴파일하지 않는다. 또 aspectjweaver·webflux·kotlinx-coroutines-reactor 를 main 의존성에 넣으면 tx(APC 종류)·sec·async 실험의 전제가 깨진다. 그래서 `build.gradle.kts` 에 전용 sourceSet `kcolab`(src/main/kotlin 중 `kcolab/**` 만)을 두고, 의존성도 `kcolabImplementation` 에만 넣었다(main 컴파일에서는 `kcolab/**` 를 뺀다).

## 실행 환경

| 항목 | 값 |
|---|---|
| JVM | Gradle toolchain JDK 25 — Microsoft OpenJDK 25.0.4.1 (build 25.0.4.1+1-LTS) |
| 빌드 | Kotlin Gradle plugin 2.3.21, Gradle 9.7.1 |
| BOM | Spring Boot 4.1.1 → Spring Framework 7.0.9, kotlinx.coroutines 1.10.2, HikariCP 7.0.2, Micrometer 1.17.1, H2 2.4.240 |
| 의존성 | starter-web(Tomcat, MVC), starter-webflux(WebClient 용), starter-jdbc, starter-aspectj, micrometer-core, kotlinx-coroutines-core/-reactor, kotlin-reflect, h2 |
| 기기 | Apple M5 Pro (arm64), 15코어 |
| 실행일 | 2026-10-08 (다른 실험과 동시 실행, 로그 머리의 load average 참고) |

## 실행

저장소에서 직접 돌리지 않고 사본에서 돌렸다.

```bash
rsync -a --delete --exclude build --exclude .gradle ~/Desktop/development/blog/ /tmp/gradle-kco/
cd /tmp/gradle-kco
./gradlew --offline -q kcoLab --args='E14'            # kotlinx-coroutines-reactor 포함
./gradlew --offline -q kcoLabNoReactor --args='E15'   # 같은 코드, 런타임 클래스패스에서 kotlinx-coroutines-reactor 만 뺌
```

처음 한 번은 새 의존성을 받으려고 `--offline` 없이 `compileKcolabKotlin` 과 `kcoLab --args='RESOLVE-ONLY'` 를 돌렸다.

## 실험 목록

| 로그(`runs/`) | 소스 | 무엇을 보나 | 결과 요지 | 본문 절(재편 후) |
|---|---|---|---|---|
| `kco-e14-spring-suspend-threads.log` | `e14/E14.kt` | MVC suspend 핸들러의 Dispatcher 와 스레드, delay(1)+sleep(200) 20건 동시 | Dispatcher=`Dispatchers.Unconfined`, 진입 `http-nio-auto-1-exec-N` → delay 뒤 `kotlinx.coroutines.DefaultExecutor` → awaitBody() 뒤 `reactor-http-nio-2` → withContext(IO) 안 `DefaultDispatcher-worker-1`(돌아온 뒤에도 그 스레드). /block 20건 4,151~4,277 ms(전부 DefaultExecutor), /block-io 213~214 ms. reactor 없으면 `NoClassDefFoundError: kotlinx/coroutines/reactor/MonoKt` 로 500 | Spring은 suspend 핸들러를 Dispatchers.Unconfined에서 시작한다 / ① 블로킹 호출은 Dispatcher가 대신 옮겨 주지 않는다 |
| `kco-e15-transactional-suspend.log` | `e15/E15.kt` | JDBC `@Transactional suspend fun` | reactor 있음: TM begin·COMMIT 이 본문 첫 줄 전에 끝나고 본문 전체가 `isActualTransactionActive=false`, 예외에도 두 행 남음. reactor 없음: 첫 suspension 까지만 Transaction(첫 줄 true), delay 에서 COMMIT, 이후 false, 두 행 남음. 대조 non-suspend @Transactional 은 ROLLBACK, 행 0 | JPA의 @Transactional을 suspend 함수에 붙이면 본문이 Transaction 밖에서 돈다 |
| `kco-e18-hikari-vs-io.log` | `e18/E18.kt` | IO(64) vs IO.limitedParallelism(10,"db") + Hikari 10, 요청당 커넥션 2개 | IO: 대기 피크 61·54·54, 스레드 64 / view: 대기 0, 스레드 10, 총 소요 비슷(1,441~1,541 vs 1,446~1,448 ms). Cm=2: 동시 5·9 성공, 10·12·20 은 2초 타임아웃 실패 7·8·15 | DB view는 커넥션 풀 크기에 맞춘다 |
| `kco-e25-spring-proxy-suspend.log` | `e25/E25.kt` | @Cacheable·@Async suspend | reactor 있음: @Cacheable 동작(본문 1회, 캐시에 "item-1"). reactor 없음: 캐시에 `COROUTINE_SUSPENDED` 가 저장되고 둘째 호출이 재개되지 않음. @Async 는 두 구성 모두 `IllegalArgumentException: Invalid return type for async method (only Future and void supported): class java.lang.Object` | JPA의 @Transactional을 suspend 함수에 붙이면 본문이 Transaction 밖에서 돈다 (끝 문단, @Cacheable·@Async) |
| `kco-e26-timed-suspend.log` | `e26/E26.kt` | @Timed suspend { sleep(100); delay(400) } | reactor 있음: timer mean 7.1 ms(Mono 생성까지, (a)). reactor 없음: 108.0 ms(첫 suspension 까지, (b)). try/finally 타이머 515 ms | AOP 타이머는 suspend 함수의 Mono 생성까지만 잰다 |
| `kcolab-snippets-compile.log` | `snippets/Snippets.kt` | 본문 요지 코드 컴파일(OrderDao, DispatcherConfig, ItemClient, ExcelJobs) | 컴파일 성공(실행하지 않음) | withContext(Dispatchers.IO)는 블로킹 호출 바로 바깥에 건다 (OrderDao) / DB view는 커넥션 풀 크기에 맞춘다 (DispatcherConfig) / suspend 호출과 fan-out에는 Semaphore로 상한을 건다 (ItemClient) / Bean은 scope를 필드 하나로 들고 @PreDestroy에서 닫는다 (ExcelJobs) |

이 디렉터리와 `build.gradle.kts` 의 `kcolab` sourceSet·`kcoLab`/`kcoLabNoReactor` 태스크는 커밋 7d42abb 로 들어갔다.
