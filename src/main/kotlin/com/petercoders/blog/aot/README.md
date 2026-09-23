# JVM 기동 시간이 쓰이는 곳 — AOT 캐시가 저장하는 것과 버리는 것

블로그 글의 실험 코드와 실행 로그 전문. 본문에는 요지 명령과 결정적인 출력 몇 줄만 실려 있다.

## 실행 환경

| 항목 | 값 |
|---|---|
| CPU / 아키텍처 | Apple M5 Pro · arm64 · 코어 15개 |
| OS | macOS (Darwin 25.6.0) |
| 캐시 라인 | 128 B |
| JDK 25 | Microsoft build `25.0.4.1+1-LTS` (`~/Library/Java/JavaVirtualMachines/ms-25.0.4.1/Contents/Home`) |
| JDK 26 | `26.0.2.1+1-7` (`~/Library/Java/JavaVirtualMachines/openjdk-26.0.2.1/Contents/Home`) |
| 앱 | Spring Boot 4.1.1 + Kotlin 2.3.21, 의존 `spring-boot-starter`·`kotlin-reflect` |
| 진입점 | `com.petercoders.blog.BlogApplicationKt` |
| jar | `build/libs/blog-0.0.1-SNAPSHOT.jar` · 14,573,458 B |

기동 벤치는 `uptime`의 load average가 코어 수보다 충분히 낮을 때만 돌렸다. e1~e9는 load 4~6, e10 재실험은 load 1.98, e11 재실험은 load 2.60~2.91에서 쟀다.

## 파일

| 파일 | 하는 일 |
|---|---|
| `bench.sh` | `bench.sh <이름> <횟수> <java> <옵션…>` — 같은 조건을 N회 돌려 벽시계 ms와 `Started … in` 값을 기록하고 중앙값을 낸다 |
| `setup.sh` | fat jar를 `application/`으로 펼치고 `-XX:AOTMode=record` → `create`로 `blog.aotconf`·`blog.aot`를 만든다 |
| `Marker.java` | 클래스패스 변경(크기 변화) 실험용 더미 클래스 |
| `runs/` | 아래 표의 로그 전문 |
| `runs/work/` | 펼친 배치(`application/`), `blog.aot`, `blog.jsa`, `blog.aot.map`, record/create 로그 |

빌드는 저장소 루트에서 `./gradlew bootJar`.

## 실험 목록

| id | 로그 | 무엇을 보나 | 본문 절 |
|---|---|---|---|
| e1 | `runs/e1-env.log` | 실행 환경·JDK 두 빌드·jar 크기 | 도입 |
| e2 | `runs/e2-baseline-fatjar.log` | fat jar 베이스라인 기동 중앙값 (715 ms) | 기동 로그가 재지 않는 시간 |
| e3 | `runs/e3-classload-perf.log` | `class+load` 출처별 분포, `perf+class+link` 카운터, `Create VM` | 기동 로그가 재지 않는 시간 |
| e4 | `runs/e4-aot-fatjar.log` | fat jar + AOT 캐시 — `unregistered=3019`, `aot-linked=0`, 715 → 397 ms | 캐시 뜨기 |
| e5 | `runs/e5-aot-extracted.log` | 펼친 배치 ± 캐시 — 482 → 249 ms | 캐시 뜨기 |
| e6 | `runs/e6-aotmap.log` | `-Xlog:aot+map=trace` 덤프의 영역·항목 집계 | 캐시 안에 든 것 |
| e7 | `runs/e7-perf-with-cache-raw.log` | 캐시 적용 시 `clinit`·`link methods` 카운터 | 캐시 안에 든 것 |
| e8 | `runs/e8-replay-training.log` | `AOTReplayTraining` on/off — 컴파일 수·tier 4·벽시계 | 프로파일까지 얹기 |
| e9 | `runs/work/single-step.log` | `-XX:AOTCacheOutput` 단일 스텝의 하위 실행 | 프로파일까지 얹기 |
| e10 | `runs/e10-invalidation.log` | 무효화 4조건(mtime·크기·JDK 빌드·헤더 플래그) × `auto`/`on`, 유효 vs 무효 기동 시간 | 깨지는 조건 |
| e10 (1차) | `runs/e10-control-*.log`, `runs/e10d-jdk26-*.log`, `runs/e10e-compact-*.log` | 같은 조건의 1차 실행 로그 | 깨지는 조건 |
| e11 | `runs/e11-cds-vs-aot-idle.log` | `-Xshare:off` / 동적 CDS / AOT 캐시 15라운드 라운드로빈 (542 / 294 / 223 ms) | CDS 와의 거리 |
| e12 | `runs/e12-jdk26-codecache.log`, `runs/e12b-jdk25-codecache.log` | JDK 26·25 의 어댑터·스텁 코드 캐시 카운트 (어댑터 512 / 517, C1·C2 블롭 0) | 프로파일까지 얹기 |
| e11 (1차) | `runs/e11-cds-vs-aot.log`, `runs/e11-cds-vs-aot-2.log` | 부하가 높던 때의 같은 실험 — 절대값이 흔들린 기록 | — |

## 재현 명령

```bash
AOT=$(pwd)
J25=~/Library/Java/JavaVirtualMachines/ms-25.0.4.1/Contents/Home/bin/java
J26=~/Library/Java/JavaVirtualMachines/openjdk-26.0.2.1/Contents/Home/bin/java
JAR=../../../../../../../build/libs/blog-0.0.1-SNAPSHOT.jar

# e2·e3 — fat jar 베이스라인과 카운터
./bench.sh fatjar-baseline 5 $J25 -jar $JAR
$J25 -Xlog:class+load:file=runs/fatjar-classload.log -Xlog:perf+class+link -Xlog:startuptime -jar $JAR

# e5 — 펼친 배치 ± 캐시
./setup.sh
W=runs/work; APP=$W/application/blog-0.0.1-SNAPSHOT.jar
./bench.sh extracted-baseline 5 $J25 -jar $APP
./bench.sh extracted-aot 5 $J25 -XX:AOTCache=$W/blog.aot -jar $APP

# e10 — 무효화
cd runs/work
touch application/blog-0.0.1-SNAPSHOT.jar
$J25 -XX:AOTCache=blog.aot -jar application/blog-0.0.1-SNAPSHOT.jar; echo "auto exit=$?"
$J25 -XX:AOTCache=blog.aot -XX:AOTMode=on -jar application/blog-0.0.1-SNAPSHOT.jar; echo "on exit=$?"

# e12 — 어댑터·스텁 코드 캐시 (JDK 26 / 25)
$J26 -XX:AOTCache=blog26.aot -Xlog:aot+codecache+init=debug -jar $APP
$J25 -XX:AOTCache=blog.aot   -Xlog:aot+codecache+init=debug -jar $APP

# e11 — 세 아카이브 비교 (라운드로빈)
$J25 -Xshare:off -jar $APP
$J25 -Xshare:on -XX:SharedArchiveFile=blog.jsa -jar $APP
$J25 -XX:AOTCache=blog.aot -jar $APP
```

`runs/work/` 안의 `.aot`·`.jsa`·`.map`은 수십~수백 MB다. 커밋 대상이 아니다.
