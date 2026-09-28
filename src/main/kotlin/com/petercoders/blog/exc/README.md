# exc — 3편 "예외 처리는 한 곳이 아니라 경계마다 다르다" 실험 코드

글: [예외 처리는 한 곳이 아니라 경계마다 다르다](https://velog.io/@petercoders)(SPRING-INTERNALS 3편)

이 디렉터리는 그 글에 실린 모든 실측(X1~X9)의 소스와 원본 로그를 담는다. 본문에는 요지 코드와 결정적 출력 몇 줄만 실려 있고, 전문은 여기에 있다.

## 실행 환경

| 항목 | 값 |
|---|---|
| CPU | Apple M5 Pro, physicalcpu 15 / logicalcpu 15 |
| 캐시라인 | `hw.cachelinesize` 128 |
| OS | macOS, Darwin 25.6.0 |
| JDK(Gradle 툴체인) | Microsoft Build of OpenJDK 25.0.4.1+1-LTS (`~/Library/Java/JavaVirtualMachines/ms-25.0.4.1/Contents/Home`) |
| JDK(`/usr/bin/java`, 참고용) | OpenJDK 26.0.2.1 — 실제 실행은 Gradle 툴체인 25.0.4.1을 탄다 |
| Kotlin | 2.3.21 |
| Spring Boot | 4.1.1 (Spring Framework 7.0.9) |
| DB | H2 인메모리 |

절대 시간·순번은 이 기기·이 JVM 조합에 묶여 있다. 다른 환경으로 이식할 수 있는 것은 상태 코드·바디 형식·승패 관계뿐이다.

## 실험 목록

| 파일/마커 | 무엇을 보나 | 본문 절 |
|---|---|---|
| X1 (`ExcLab.kt`) | 컨테이너에 등록된 `HandlerExceptionResolverComposite`·advice·필터 실물과 order | 겹 안에서 누가 이기는가 |
| X2 (`ExcProbes.kt`: `LocalHandlerController`, `AdviceA`, `AdviceB`) | 컨트롤러 로컬 vs `@Order(1)` vs `@Order(2)` 승자 | 겹 안에서 누가 이기는가 |
| X3 (`ExcLab.kt`) | `spring.mvc.problemdetails.enabled` on/off에 따른 빈 등록과 응답 바디 | 겹 안이 만드는 응답 바디 |
| X4 (`ExcProbes.kt`: `InheritOnlyAdvice`) | `ResponseEntityExceptionHandler` 상속만 했을 때 자동으로 덮이는 예외 | 겹 안이 만드는 응답 바디 |
| X5 (`ExcProbes.kt`: `BoomFilter`) | 필터에서 던진 예외가 advice에 닿는지 | 겹 밖 ① — 필터 |
| X6 (`ExcProbes.kt`: `TraceFilter`) | 같은 요청의 디스패치 타입 기록(`REQUEST`/`ERROR`) | 겹 밖 ① — 필터 |
| X7 / X7b | `@Async` 반환 타입(void/Future 방치/`join()`)별 예외 행선지, `AsyncConfigurer` 교체 | 겹 밖 ② — 비동기 스레드 (X7·X7b 실측, `runs/x7.log`) |
| X8 (`ExcProbes.kt`: `ExcService.writeThenFail`) | `TransactionSynchronization` 네 콜백(`beforeCommit`/`beforeCompletion`/`afterCommit`/`afterCompletion`)에서 던진 예외의 행선지와 커밋 여부 | 겹 밖 ③ — 트랜잭션 커밋 |
| X9 | 다섯 경계(컨트롤러/필터/`@Async`/`beforeCommit`/`afterCompletion`)를 한 표로 종합 | 안 닿는 자리를 대신 받는 것 (X9 실측, `runs/x9.log`) |

## 빌드·실행 명령

```bash
# 최초 1회 — spring-boot-starter-web 등 신규 의존성을 받아야 해서 --offline 을 못 쓴다
./gradlew -q excLab

# 이후 재실행 — 마커 하나만 다시 돌릴 때
./gradlew --offline -q excLab --args='X7'
./gradlew --offline -q excLab --args='X7b'
./gradlew --offline -q excLab --args='X9'
./gradlew --offline -q excLab --args='X1'
./gradlew --offline -q excLab --args='X2'
./gradlew --offline -q excLab --args='X3'
./gradlew --offline -q excLab --args='X4'
./gradlew --offline -q excLab --args='X5'   # X5/X6 을 함께 찍는다
./gradlew --offline -q excLab --args='X8'
```

메인 클래스는 `com.petercoders.blog.exc.ExcLabKt`(`build.gradle.kts`의 `excLab` 태스크). 소스는 `ExcLab.kt`(실험 드라이버)와 `ExcProbes.kt`(컨트롤러·advice·필터·서비스 프로브)로 나뉜다.

## `runs/` 로그 색인

| 로그 | 마커 | 비고 |
|---|---|---|
| `runs/x1.log` | X1 | 리졸버·advice·필터 실물 |
| `runs/x2.log` | X2 | 로컬/AdviceA/AdviceB 승자 |
| `runs/x3.log` | X3 | problemdetails on/off |
| `runs/x4.log` | X4 | REEH 상속만 했을 때 |
| `runs/x5.log` | X5, X6 | 필터 예외 + 디스패치 기록(X6은 X5 실행 중 함께 찍힘) |
| `runs/x6.log` | X6 | X5 로그에서 `[X6]` 이후 구간만 발췌 |
| `runs/x8.log` | X8 | 트랜잭션 동기화 콜백 네 자리 |
| `runs/x7.log` | X7, X7b | `@Async` 반환 타입 세 갈래 + 핸들러 교체 카운터 |
| `runs/x9.log` | X9 | 경계 다섯 자리 종합 |

이 디렉터리는 작성 시점 기준 git에 커밋되지 않은 상태다. add/commit/push는 사용자가 직접 한다.
