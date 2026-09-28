# async — 5편 "비동기로 넘긴 순간 예외와 종료의 계약이 바뀐다" 실험 코드

SPRING-INTERNALS 5편(`@Async`·`@Scheduled`)에 실린 실측의 소스와 원본 로그를 담는다.
본문(velog 게시 후 이 줄에 링크 추가 예정)에는 요지 코드와 결정적 출력 몇 줄만 실려 있고, 전문은 여기에 있다.

## 실행 환경

| 항목 | 값 |
|---|---|
| 기기 / CPU | Apple M5 Pro, 15 코어 |
| 캐시라인 | `hw.cachelinesize` 128 |
| OS | macOS, Darwin 25.6.0 |
| JDK(Gradle 툴체인) | 25 (Microsoft OpenJDK 25.0.4.1) — 셸 기본 `java -version`은 26.0.2.1이지만 `asyncLab` 태스크는 툴체인 25 위에서 실행됨(`build/`의 `.class` 매직 넘버 major 69로 확인) |
| Kotlin | 2.3.21 |
| Spring Boot / Framework | 4.1.1 / 7.0.9 |
| DB | H2 인메모리 (`jdbc:h2:mem:asynclab;DB_CLOSE_DELAY=-1`) |

## 실험 목록

| 마커 | 무엇을 보나 | 로그 | 본문 절 | 본문 반영 |
|---|---|---|---|---|
| A1 | 가상 스레드 off — `applicationTaskExecutor`·`taskScheduler` 실물과 풀 설정 | `runs/A1.log` | 실행자의 정체 | 반영(결정적 출력) |
| A1b | `spring.threads.virtual.enabled=true` — 같은 이름, 다른 클래스(A1과 같은 실행에 함께 기록) | `runs/A1.log` | 실행자의 정체 | 반영(결정적 출력) |
| A1c | 사용자 `Executor` 빈만 등록했을 때 / `spring.task.execution.mode=force`일 때 | `runs/A1c.log` | 실행자의 정체 | 반영(결정적 출력) |
| A2 | `get()`·`join()`·`Future.get()` 세 갈래의 예외 클래스명 | `runs/A2.log` | 반환 타입과 행선지 | 반영(결정적 출력) |
| A2c | `get(1, SECONDS)` 타임아웃 — 원인 없는 `TimeoutException` | `runs/A2c.log` | 반환 타입과 행선지 | 반영(결정적 출력) |
| A3 | 기본 `pool.size=1`, `fixedRate` 2s · 본문 3s — 겹침 여부 | `runs/A3.log` | 스케줄러 계약 | 반영(결정적 출력) |
| A3b | `spring.task.scheduling.pool.size=2` 비교군 | `runs/A3b.log` | 스케줄러 계약 | 반영(결정적 출력) |
| A3c | 가상 스레드 on — `fixedRate` 대 `fixedDelay` | `runs/A3c.log` | 스케줄러 계약 | 반영(결정적 출력) |
| A4 | 종료 기본값에서 실행 중 `@Async` 작업의 카운터·H2 행 | `runs/A4.log` | 종료 | 반영(결정적 출력) |
| A4b | `await-termination=true` + `period=10s` 비교군 | `runs/A4b.log` | 종료 | 반영(결정적 출력) |

## 파일별 빌드·실행 명령

메인 드라이버는 `com.petercoders.blog.async.AsyncLabKt`(`build.gradle.kts`의 `asyncLab` 태스크)다.
소스는 `AsyncLab.kt`(실험 드라이버, 각 마커의 `runXxx()`)와 `AsyncProbes.kt`(`@Async`·`@Scheduled` 프로브 빈)로 나뉜다.

```bash
cd ~/Desktop/development/blog

# 마커 하나 실행 + 로그 저장
./gradlew --offline -q asyncLab --args='A1' \
  | tee src/main/kotlin/com/petercoders/blog/async/runs/A1.log

# 전체 마커를 한 번에
for m in A1 A1c A2 A2c A3 A3b A3c A4 A4b; do
  ./gradlew --offline -q asyncLab --args="$m" \
    | tee "src/main/kotlin/com/petercoders/blog/async/runs/$m.log"
done
```

시간을 쓰는 항목(A3 계열 11초, A4 계열 수 초)은 초 단위 굵기로만 판정한다. ms 벤치마크는 하지 않는다.
macOS(APFS)는 기본이 대소문자 비구분 파일시스템이라 로그 파일명의 대소문자(`A1.log` vs `a1.log`)가 실제로는 같은 파일을 가리킬 수 있다 — 새 마커의 로그 파일을 만들 때는 기존 파일명과 충돌하지 않는지 먼저 `ls`로 확인할 것.

## `runs/` 로그 색인

작성 시점 기준 디렉터리에 실제로 있는 파일:

| 파일 | 마커 |
|---|---|
| `runs/A1.log` | A1, A1b |
| `runs/A1c.log` | A1c |
| `runs/A2.log` | A2 |
| `runs/A2c.log` | A2c |
| `runs/A3.log` | A3 |
| `runs/A3b.log` | A3b |
| `runs/A4.log` | A4 |
| `runs/A4b.log` | A4b |

`A3c`는 로그 파일이 없다(로컬에서 아직 실행되지 않음).

이 디렉터리는 작성 시점 기준 git에 커밋되지 않은 상태다. add/commit/push는 사용자가 직접 한다.
