# JDK AOT Cache: Spring Boot에 켜기 전에 확인할 것

블로그 글의 실험 코드와 실행 로그 전문이다. 본문에는 요지 명령과 결정적인 출력 몇 줄만 실려 있다.
실험은 두 번에 나눠 돌렸다. 1차(e1~e12, 2026-09)는 `setup.sh`·`bench.sh`로, r2(e13~e22, 2026-10-08)는 아래 고정 기준 위에서 `r2-*.sh`·`eNN.sh`로 돌렸다. 2026-10-10 감사 라운드에서 e13-g(원본 재집계)와 e23(빌드 시간 재측정), e24(같은 배치끼리의 clinit 카운터), e25(JDK 패치별 jar 검사, JDK-8377932)를 더했다.

## 고정 기준

| 항목 | 값 |
|---|---|
| 측정 jar | `build/libs/blog-0.0.1-SNAPSHOT.jar` · 14,573,458 B · 2026-09-08 23:50:45 · sha256 `89abfdeba6c568ea8fdd6d34a69a238d7a2a602185e1d3131f607d6e81152b7b` · `BOOT-INF/lib` 23개(펼치면 `lib/` 22개, tomcat 없음) |
| 앱 코드·빌드 설정 | 커밋 [`f3c9bfb`](https://github.com/petercorders/blog/tree/f3c9bfb/src/main/kotlin/com/petercoders/blog/aot) — `build.gradle.kts` 의존은 `spring-boot-starter`·`kotlin-reflect` 둘 |
| 지금 HEAD 와의 차이 | `e56310e`가 `spring-boot-starter-jdbc`와 `h2`(runtimeOnly)를, `5c4fccb`가 `spring-boot-starter-security`·`spring-boot-starter-web`을 더했다. 지금 HEAD로 `bootJar`하면 Tomcat이 떠서 앱이 스스로 끝나지 않는 다른 앱이 된다 |
| r2 규칙 | `./gradlew bootJar`를 돌리지 않는다. 고정 jar 사본(`runs/pinned/`)만 쓰고, 캐시는 `runs/work-r2/`에 새로 만든다. 측정 전에 `-XX:AOTMode=on` 게이트(exit 0)를 통과한 캐시만 쓴다 |
| 변형 앱 | e14(web)·e20(JDK 21)·e22(설정 바인딩)는 `variants/build-variant.sh`가 `git archive f3c9bfb`로 꺼낸 트리에 한 가지만 더해 `/tmp/gradle-aot-<name>/`에서 `--offline` 빌드한다 |

옛 `runs/work`의 앱 jar mtime은 2026-09-23 20:01:05로 바뀌어 있었고 캐시가 기록한 값은 2026-09-08 23:50:45였다. 그래서 그 뒤의 `blog.aot`는 무효였다. r2 사전 절차에서 `touch -t 202609082350.45`로 되돌렸고 `AOTMode=on`이 다시 exit 0이 되는 것을 확인했다(`runs/r2-pre-oldcache.log`).

## 실행 환경

| 항목 | 값 |
|---|---|
| CPU / 아키텍처 | Apple M5 Pro · arm64 · 코어 15개 |
| OS | macOS (Darwin 25.6.0) |
| JDK 25 (주 측정) | Microsoft `25.0.4.1+1-LTS` (`~/Library/Java/JavaVirtualMachines/ms-25.0.4.1`) |
| JDK 26 (대조) | OpenJDK `26.0.2.1+1-7` (`~/Library/Java/JavaVirtualMachines/openjdk-26.0.2.1`) |
| JDK 27 (e16·e19) | Temurin `27+35` — tarball(sha256 `a59626c9…`)을 풀어 절대경로로 실행 |
| JDK 21 (e20) | Temurin `21.0.12.1+1-LTS` — tarball(sha256 `3623232f…`)을 풀어 절대경로로 실행 |
| JDK 25 다른 빌드 (e17) | Temurin `25.0.4.1+1` arm64·x64(Rosetta 2), `25.0.3+9`, `25.0.2+10` arm64 |
| JDK 패치별 (e25) | Temurin `25.0.2+10`·`25.0.3+9`·`25.0.4.1+1` arm64 tarball(`JDKS=<푼 폴더>`), Microsoft `25.0.4.1+1-LTS`, OpenJDK `26.0.2.1+1-7` |
| 앱 | Spring Boot 4.1.1 + Kotlin 2.3.21, 진입점 `com.petercoders.blog.BlogApplicationKt` |
| agent (e16) | `dd-java-agent` 1.67.0(Maven Central, sha1 `8da71687…`), 직접 만든 Java agent 둘, native agent 하나 |

`java_home -v`는 설치되지 않은 버전을 상위로 폴백하므로 쓰지 않았다. 로그마다 머리에 `java -version`을 남겼다.

### 부하

r2는 다른 작업과 같은 기기에서 돌았고 load가 크게 흔들렸다(최대 80). 그래서 기동 시간은 같은 라운드 안에서 조건을 돌아가며 재고, **같은 라운드 기준 조건 대비 비율의 중앙값**과 라운드별 승패로만 비교한다. 절대 ms는 비교에 쓰지 않는다. 로그마다 라운드별 `uptime`을 남겼다.

| 실험 | load1 (min / median / max) |
|---|---|
| e1 · e2 · e4 · e5 · e8 (1차) | 5.16 · 5.49 · 7.38 · 5.95 · 4.59 (실험당 1회 기록) |
| e10 (1차 재실험) · e11 idle | 1.98 · 2.60~2.91 |
| e13-a (30라운드) · 확인 실행(30라운드) | 8.78 / 10.32 / 30.22 · 3.70 / 8.85 / 11.05 |
| e14-measure (15라운드) | 11.0~13.1 (라운드별 기록) |
| e19-b (15라운드) | 4.13 / 13.85 / 15.78 |
| e20-b (15라운드) | 5.36 / 6.76 / 8.55 |
| e16-rr (10라운드) | 5.79 / 6.51 / 7.46 |
| e23 (20라운드) · e24 (5라운드) · e25 | 4.37~5.54(마지막 측정 9.75) · 13.60~13.96 · 6.25~6.66(시작·끝 기록) |

## 파일

| 파일 | 하는 일 |
|---|---|
| `bench.sh` | (1차) 같은 조건을 N회 돌려 벽시계 ms와 `Started … in` 값의 중앙값을 낸다 |
| `setup.sh` | (1차) fat jar를 펼치고 record → create 로 `blog.aot`를 만든다 |
| `Marker.java` | 클래스패스 크기 변경 실험용 더미 클래스(e10b, e13-c) |
| `r2-env.sh` | r2 공통: JDK 경로, 고정 jar, 로그 머리(`header`), 한 번 실행(`run_once`), 게이트(`gate`), class+load 집계 |
| `r2-pre.sh` | r2 사전 절차: 옛 캐시 무효 원인 재현·복원, `work-r2`에 펼치고 한 줄 방식(onRefresh)으로 캐시 6개 생성·게이트 |
| `e13.sh` | 같은 라운드 라운드로빈, class+load 비율, 크기 조건 재실행, 빌드 게이트, 크기·mtime 이 같은 lib 교체 |
| `e14.sh`, `e14_driver.py` | web 변형 앱의 training 방식별 캐시와 첫 요청 측정(드라이버가 `server.port=0` 포트를 로그에서 읽는다) |
| `e15.sh` | training·운영 옵션 불일치 격자(GC·Heap·헤더 플래그), ZGC·streamable 캐시 |
| `e16.sh`, `e16/`, `e16-native.sh`, `e16-rr.sh` | agent 구성 × AOT Cache(Java agent 둘·dd-java-agent·native agent) |
| `e17-local.sh` | docker 대신 로컬로 확인한 것: 다른 벤더·패치·아키텍처의 JDK 25, 파일 복사와 mtime, 한 줄 방식 메모리 |
| `e18.sh` · `e19.sh` · `e20.sh` · `e21.sh` · `e22.sh` | CDS+AOT 동시 지정 / JDK 27 / JDK 21 / dynamic Proxy / training 설정 |
| `e13f.sh` | 새 실행 없이 e13-b 의 E1 원본 class+load 와 e21 의 JDK 25 training 출력을 읽어 캐시 밖 198줄을 분류한다 |
| `e13g.sh` | 새 실행 없이 e3 의 fat jar class+load 와 e13-b 의 F0·E1 원본을 source 별로 다시 센다(기본 CDS 밖 JDK 클래스·hidden class) |
| `e23.sh` | web 변형 앱에서 onRefresh 캐시를 3단계(record→create)와 한 줄(`AOTCacheOutput`)로 같은 라운드에 번갈아 만들며 빌드 시간을 잰다 |
| `e24.sh` | F0/F1/E0/E1 을 같은 라운드에서 `-Xlog:perf+class+link`로 띄워 clinit·link methods 이벤트 수를 같은 배치끼리 대조한다(e3·e7 은 배치가 달랐다) |
| `e25.sh` | JDK 패치별로 jar 를 바꾼 뒤 캐시가 버려지는가(JDK-8377932). `one`(앱 jar touch·spring-core 클래스 삭제), `quiet`(같은 조건 + logging system 을 Logback 으로 고정), `hello`(JBS 의 HelloWorld 재현), `why`·`whyq`(3단계 create 중 앱 클래스패스에서 평소대로 load 된 클래스) |
| `rr_summary.py` | 라운드로빈 로그 요약(비율 중앙값·승패) |
| `variants/` | 변형 앱 overlay(`*.kt.txt`, 메인 빌드에 섞이지 않도록 확장자를 바꿔 둔다)와 `build-variant.sh` |
| `runs/` | 아래 표의 로그 전문 |

## 실험 목록 — 1차 (2026-09)

`본문 절` 열은 원고의 절 제목 그대로다(여러 절에서 쓰면 원고 등장 순서대로 ` · `로 잇는다). `검증 메모`는 마무리 아래 접어 둔 `<details>` 블록이다.

| id | 로그 | 무엇을 보나 | 본문 절 |
|---|---|---|---|
| e1 | `runs/e1-env.log` | 실행 환경·JDK 두 빌드·jar 크기 | 도입(실험 환경) · 검증 메모 |
| e2 | `runs/e2-baseline-fatjar.log` | fat jar 기동 5회 중앙값(벽시계 715 ms, Started 0.511 s) | 도입 · 그 한 줄이 실제로 바꾸는 것 · 검증 메모 |
| e3 | `runs/e3-classload-perf.log` | `class+load` 5,804줄의 출처(기본 CDS 1,379 / `jar:nested` 2,927), 그 중 `run()` 시작(uptime 약 0.170 s) 전 2,047줄 | 도입 · 그 한 줄이 실제로 바꾸는 것 · 켠 다음 운영에서 보는 것 · 검증 메모 |
| e4 | `runs/e4-aot-fatjar.log` | fat jar + 캐시 — unregistered 3,019(aot-linked 0), boot 2,133·platform 6·app 101 aot-linked | ① fat jar 그대로는 앱 클래스가 link되지 않는다 · 검증 메모 |
| e5 | `runs/e5-aot-extracted.log` | 펼친 배치 ± 캐시(482 → 249 ms, e2·e4 와 서로 다른 5회 배치), 캐시 적용 class+load shared 5,691/5,806(98%) | 캐시는 클래스 준비만 대신한다 · 펼쳐도 캐시 밖에 남는 클래스는 확인만 하면 된다 · 켠 다음 운영에서 보는 것 · 도입 체크리스트 · 검증 메모 |
| e6 | `runs/e6-aotmap.log` | `-Xlog:aot+map=trace` 덤프의 영역·항목 집계(Heap 영역 45,017 객체, inited 884 중 hidden 733) | 캐시는 클래스 준비만 대신한다 · 펼쳐도 캐시 밖에 남는 클래스는 확인만 하면 된다 · 검증 메모 |
| e7 | `runs/e7-perf-with-cache-raw.log` | 캐시 적용 시 `clinit`·`link methods` 카운터(clinit 3,702). 명령줄이 남지 않았고 Started 0.166 s 로 보아 펼친 배치 + 1차 캐시다. e3(fat jar 무캐시 4,615)와는 배치가 달라 본문은 같은 배치 대조인 e24 를 쓴다 | 검증 메모 |
| e8 | `runs/e8-replay-training.log` | `AOTReplayTraining` on/off — 컴파일 수·tier 4·벽시계 | ② training run이 캐시에 담길 범위를 정한다 |
| e9 | `runs/work/single-step.log` | `-XX:AOTCacheOutput` 한 줄 방식의 자식 실행(136행 `Picked up JAVA_TOOL_OPTIONS`) | 그 한 줄이 실제로 바꾸는 것 |
| e10 | `runs/e10-invalidation.log` | 무효화 4조건 × `auto`/`on`, 유효 vs 무효 기동 시간 | ③ 캐시는 조용히 버려진다 · 배포에서 캐시를 깨는 변경과 검사를 지나치는 변경 · 켠 다음 운영에서 보는 것 · 검증 메모 |
| e10 (1차) | `runs/e10-control-*.log`, `runs/e10d-jdk26-*.log`, `runs/e10e-compact-*.log` | 같은 조건의 1차 실행 | — (본문은 `e10-invalidation.log`를 인용) |
| e11 | `runs/e11-cds-vs-aot-idle.log` | `-Xshare:off` / 동적 CDS / AOT Cache 15라운드 라운드로빈(같은 라운드 벽시계 비율 중앙값: 동적 CDS 0.54, AOT Cache 0.41, AOT/CDS 0.76·15/15 · 이 기기 중앙값 542 / 294 / 223 ms) | 이미 CDS를 쓰고 있다면 AOT Cache가 link까지 더 줄인다 · 도입 체크리스트 · 검증 메모 |
| e11 (1차) | `runs/e11-cds-vs-aot.log`, `runs/e11-cds-vs-aot-2.log`, `runs/e11-baseline.log`·`e11-cds.log`·`e11-aot.log` | 부하가 높던 때의 같은 실험(뒤 셋은 라운드로빈 전에 `bench.sh`로 5회씩 차례로 잰 원본) | — |
| e12 | `runs/e12-jdk26-codecache.log`, `runs/e12b-jdk25-codecache.log` | 어댑터·스텁 코드 캐시(어댑터 512 / 517, C1·C2 0) | 캐시는 클래스 준비만 대신한다 · 검증 메모 |
| nowcheck | `runs/nocache-nowcheck.log`, `runs/broken-cache-nowcheck.log`, `runs/valid-cache-nowcheck.log` | 2026-09-23 재확인 3조건(0.339 / 0.355 / 0.352 s). **broken 이 20:01:05 에 앱 jar 를 touch 한 뒤 valid 가 20:01:16 에 돌아, valid 도 무효 캐시였다**(`runs/r2-pre-oldcache.log`). 세 값은 근거로 쓰지 않는다 | 검증 메모(옛 측정의 재해석) |
| 원본 | `runs/fatjar-baseline.log`·`fatjar-aot.log`·`extracted-baseline.log`·`extracted-aot.log`, `runs/fatjar-classload.log`·`fatjar-perf.log` | `bench.sh`가 남긴 원본 출력(e2·e4·e5 로그에 그대로 실려 있다)과 e3 의 class+load 5,804줄·`startuptime`·`perf+class+link` 원본 | 검증 메모(e3 의 2,047 을 `fatjar-classload.log`에서 계산) · — (나머지는 e2~e5 로 인용) |
| 원본 | `runs/work/*.log`, `runs/work-fat/*.log` | `setup.sh`의 record·create·class+load 출력(펼친 배치·fat jar)과 e9 의 `single-step.log` | — (e4·e5·e9 로 인용) |

e10b 1차 시도의 `size: 16441 -> 16442 … AOTMode=auto exit=1 stderr_lines=1`은 캐시가 아니라 jar 자체가 깨진 것이다. 앱 jar 끝에 1바이트를 덧붙이면 캐시 유무와 상관없이 `Error: Invalid or corrupt jarfile`, exit 1, stderr 1줄이 그대로 재현된다(`runs/e13-c-size.log`).

## 실험 목록 — r2 (2026-10-08)

| id | 로그 | 무엇을 보나 | 본문 절 |
|---|---|---|---|
| pre | `runs/r2-pre-oldcache.log` | 옛 캐시 무효 원인(앱 jar mtime) 재현과 복원, nowcheck 시각 대조 | ③ 캐시는 조용히 버려진다 · 검증 메모 |
| pre | `runs/r2-pre-caches.log` | work-r2 에 펼치고 한 줄 방식(onRefresh)으로 JDK 25·26 캐시 6개 생성, 게이트 6/6 exit 0 | 검증 메모(실제 실행한 실험) |
| e13-a | `runs/e13-a-roundrobin.log` (+ `runs/e13-a-roundrobin-try1.log` 15라운드, `runs/e13-a-roundrobin-confirm.log` 30라운드 확인 실행) | F0 fat jar / F1 fat jar+캐시 / E0 펼침 / E1 펼침+캐시 / E2 펼침+깨진 캐시, 30라운드 | 도입 · ① fat jar 그대로는 앱 클래스가 link되지 않는다 · ③ 캐시는 조용히 버려진다 · 도입 체크리스트 |
| e13-b | `runs/e13-b-classload.log` | 조건별 `shared objects file` 비율, '켠 다음 운영에서 보는 것' 절의 class+load 확인 명령 | 캐시는 클래스 준비만 대신한다 · ① fat jar 그대로는 앱 클래스가 link되지 않는다 · 펼쳐도 캐시 밖에 남는 클래스는 확인만 하면 된다 · ③ 캐시는 조용히 버려진다 · 켠 다음 운영에서 보는 것 · 도입 체크리스트 |
| e13-c | `runs/e13-c-size.log` | e10b 크기 조건 3회 재실행(1바이트 덧붙임 vs 클래스 추가) | 검증 메모 |
| e13-d | `runs/e13-d-gate.log` | 빌드 게이트 명령(`AOTMode=on` + onRefresh)을 유효·깨진 캐시에 | ③ 캐시는 조용히 버려진다 · 이미지 빌드 끝에서 AOTMode=on으로 한 번 띄운다 · 켠 다음 운영에서 보는 것 |
| e13-e | `runs/e13-e-samesize-lib.log` | 크기·mtime 이 같은 lib jar 에서 클래스를 지웠을 때 | 배포에서 캐시를 깨는 변경과 검사를 지나치는 변경 |
| e13-f | `runs/e13-f-nonshared-198.log` | E1(펼침+onRefresh 캐시) class+load 중 캐시 밖 198줄 전부의 분류와 전문(새 실행 없음, e13-b 원본 class+load 와 e21 JDK 25 training 출력 재집계) | 펼쳐도 캐시 밖에 남는 클래스는 확인만 하면 된다 · CRaC와 native-image는 다시 만드는 조건으로 고른다 · 검증 메모 |
| e14 | `runs/e14-measure.log`, `runs/e14-diag.log`, `runs/e14-replay.log` | web 변형 앱: 캐시 없음 / onRefresh / 요청 1,000회 training 의 기동·첫 요청, 첫 요청 중 새 class+load, profile 재생 대조 | 도입 · ② training run이 캐시에 담길 범위를 정한다 · 연결과 스레드 풀 웜업은 남는다 · CRaC와 native-image는 다시 만드는 조건으로 고른다 · 도입 체크리스트 · 검증 메모 |
| e14 | `runs/e14-train.log`, `runs/e14-gate.log` | 캐시 만들기: training 을 onRefresh·`jcmd AOT.end_recording`·MXBean·SIGTERM 으로 끝내기, training·assembly 시간(부하 아래 1회, 본문은 e23 으로 대체), 캐시마다 `on` 게이트 | JDK 버전마다 그 한 줄이 다르다 · ② training run이 캐시에 담길 범위를 정한다 · training을 끝내는 방법은 넷이다 |
| e15 | `runs/e15-option-mismatch-jdk25.log`, `runs/e15-option-mismatch-jdk26.log`, `runs/e15-option-mismatch-jdk25-extra.log`, `runs/e15-option-mismatch-jdk26-extra.log` | GC·Heap·헤더 플래그 불일치 격자, ZGC 로 training 한 캐시 | JDK 버전마다 그 한 줄이 다르다 · 배포에서 캐시를 깨는 변경과 검사를 지나치는 변경 · GC 종류가 아니라 CompressedOops 상태를 맞춘다 · 켠 다음 운영에서 보는 것 |
| e16 | `runs/e16-build.log`, `runs/e16-agent-jdk25.log`, `runs/e16-agent-jdk26.log`, `runs/e16-agent-jdk27.log`, `runs/e16-native-cflh.log`, `runs/e16-rr-jdk25.log` | agent 조합 A·B·C·D × JDK 25·26·27 | 배포에서 캐시를 깨는 변경과 검사를 지나치는 변경 · agent 구성은 training과 운영에서 같이 정한다 · 이미지 빌드 끝에서 AOTMode=on으로 한 번 띄운다 · 켠 다음 운영에서 보는 것 |
| e17-A | `runs/e17-A-copy-mtime.log` | 펼친 앱 폴더를 `cp -R`·`rsync -r` / `cp -Rp`·`rsync -a`로 복사했을 때 jar mtime 과 `on` exit | 배포에서 캐시를 깨는 변경과 검사를 지나치는 변경 · 캐시는 jar와 같은 이미지 단계에서 만든다 |
| e17-B | `runs/e17-B-vendor-patch.log` | Microsoft 25.0.4.1 캐시를 Temurin 25.0.4.1·25.0.3·25.0.2 에서 | 배포에서 캐시를 깨는 변경과 검사를 지나치는 변경 · VM 배포와 multi-arch 이미지에서는 캐시를 대상 환경마다 만든다 |
| e17-B2 | `runs/e17-B2-patch-features.log` | 패치 버전별 `jcmd AOT.end_recording`·`HotSpotAOTCacheMXBean` 유무 | JDK 버전마다 그 한 줄이 다르다 |
| e17-C | `runs/e17-C-oneline-memory.log` | 한 줄 방식에서 부모·자식 JVM 합계 RSS | training run은 빌드 환경에서도 운영과 같은 조건으로 돌린다 |
| e17-F | `runs/e17-F-cross-arch.log` | arm64 캐시 ↔ x64(Rosetta 2), 아키텍처별 training 시간 | VM 배포와 multi-arch 이미지에서는 캐시를 대상 환경마다 만든다 |
| e18 | `runs/e18-cds-plus-aot.log` | `SharedArchiveFile`·`-Xshare:*` + `AOTCache` 동시 지정 | 이미 CDS를 쓰고 있다면 AOT Cache가 link까지 더 줄인다 · 검증 메모 |
| e19-a | `runs/e19-a-jdk27-fatjar.log` | JDK 27 fat jar 캐시의 저장 범위(unregistered 0, `Not loaded from "file:"` 3,515, 운영 shared 2,221/5,902) | ① fat jar 그대로는 앱 클래스가 link되지 않는다 |
| e19-b | `runs/e19-b-jdk27-roundrobin.log` | JDK 27 같은 라운드 15회(F1 0.884, E1 0.333) | ① fat jar 그대로는 앱 클래스가 link되지 않는다 |
| e19-c | `runs/e19-c-jdk27-required.log` | `AOTMode=required`·`on` × 유효·깨진 캐시, JDK 25·26 의 `Unrecognized value` | JDK 버전마다 그 한 줄이 다르다 |
| e19-d | `runs/e19-d-jdk27-multirelease.log` | JDK 27 training 의 multi-release(`ClassFile*`) warning | 펼쳐도 캐시 밖에 남는 클래스는 확인만 하면 된다 |
| e19-e | `runs/e19-e-jdk27-oopbase.log` | JDK 27 기본 캐시 `on` 20회 중 3회 실패, `AOTCompatibleOopCompression` 캐시 0/20, JDK 25·26 0/20 | JDK 버전마다 그 한 줄이 다르다 · 이미지 빌드 끝에서 AOTMode=on으로 한 번 띄운다 · 도입 체크리스트 · 검증 메모 |
| e20 | `runs/e20-a0-jdk21-pinnedjar-fails.log`, `runs/e20-a-jdk21-cds.log`, `runs/e20-b-jdk21-roundrobin.log` | JDK 21 동적 CDS(고정 jar 는 class file 69 라 Java 21 빌드 변형을 썼다) | JDK 버전마다 그 한 줄이 다르다 · JDK 21에 머문다면 펼친 배치와 동적 CDS까지다 |
| e21 | `runs/e21-jdk26-proxy.log` | dynamic Proxy 가 캐시에 실리는가(JDK 26, 대조 25) | 펼쳐도 캐시 밖에 남는 클래스는 확인만 하면 된다 · 검증 메모 |
| e22 | `runs/e22-training-config.log` | Kotlin non-null `@ConfigurationProperties` 와 training run(#36102) | 배포에서 캐시를 깨는 변경과 검사를 지나치는 변경 · training run은 빌드 환경에서도 운영과 같은 조건으로 돌린다 |
| e13-g | `runs/e13-g-source-breakdown.log` | (2026-10-10, 새 실행 없음) e3 class+load 5,804줄 = 기본 CDS 1,379 + `jar:nested` 2,927 + `jrt:/` 637(기본 CDS 밖 JDK 클래스) + `file:` launcher 73 + 실행 중 생성 788(`__JVM_LookupDefineClass__` 152, `__dynamic_proxy__` 42, lambda·TypeSwitch 594). E1 캐시에서 온 5,604줄 = JDK 1,922 + hidden 817 + 그 밖 2,865 | 도입 · 그 한 줄이 실제로 바꾸는 것 · 검증 메모 |
| e23 | `runs/e23-build-time.log` | (2026-10-10) web 변형 앱 onRefresh 캐시 빌드 시간, 같은 라운드 20회 번갈아: 3단계 training 1,347 + assembly 693 = 2,043 ms, 한 줄 2,069 ms(중앙값), 한 줄/3단계 1.013, 한 줄이 짧은 라운드 9/20. e14-train 의 (a)·(a1) 차이는 부하 때문이었다 | 캐시는 jar와 같은 이미지 단계에서 만든다 · CRaC와 native-image는 다시 만드는 조건으로 고른다 |
| e24 | `runs/e24-clinit-same-layout.log` | (2026-10-10) clinit 이벤트 수를 같은 배치끼리: 펼친 배치 E0 4,491 → E1(e13 의 onRefresh 캐시) 3,730(0.831배), fat jar F0 4,615 → F1 4,378. 5라운드 모두 같은 값, 게이트 통과 캐시 | 캐시는 클래스 준비만 대신한다 · 검증 메모 |
| e25 | `runs/e25-jar-check-by-patch.log` | (2026-10-10) 패치별 jar 검사. 이 앱(펼친 배치, onRefresh 캐시)은 Temurin 25.0.2·25.0.3·25.0.4.1, Microsoft 25.0.4.1, OpenJDK 26.0.2.1 모두 앱 jar touch 에 `on` exit 1. JBS 의 HelloWorld(`-cp`) 재현은 25.0.2·25.0.3 에서 touch 뒤에도 `on` exit 0 이고 'Archived app classpath validation' 줄이 없다(25.0.4.1·26.0.2.1 은 exit 1). create 중 앱 클래스패스에서 평소대로 load 된 클래스는 `Log4J2LoggingSystem` 1개, logging system 을 Logback 으로 고정하면 0개. 그 상태(quiet)에서 25.0.2·25.0.3 은 touch 뒤 `on` exit 0·shared 5,600/5,799, `StringUtils.class`를 지운 spring-core(2,035,755 → 2,024,945 B)도 `on` exit 0 이고 `StringUtils`가 캐시에서 올라온다(캐시 없이는 `NoClassDefFoundError`). 25.0.4.1·26.0.2.1 은 모두 거부 | JDK 버전마다 그 한 줄이 다르다 · 배포에서 캐시를 깨는 변경과 검사를 지나치는 변경 · 25.0.4와 26.0.2 아래 패치는 앱 jar 검사를 건너뛸 수 있다 · 이미지 빌드 끝에서 AOTMode=on으로 한 번 띄운다 · 켠 다음 운영에서 보는 것 · 도입 체크리스트 · 검증 메모 |
| ref | `runs/ref-kakao-tps-slide.log` | if(kakao)2024 TPS 2145 vs 1607 이 어느 구간 값인지(원본 PDF) | CRaC와 native-image는 다시 만드는 조건으로 고른다 · 검증 메모 |

## r2 결과 요약

**① 배치 (e13, JDK 25, 30라운드, Started 의 F0 대비 비율 중앙값)**: F1 fat jar + 캐시 0.646, E0 펼침 0.882, E1 펼침 + 캐시 0.310, E2 펼침 + 깨진 캐시 0.920. E1<F1 25/30, E1<E0 28/30, E2<F0 22/30, E2 vs E0 15/30(차이 없음). 확인 실행(30라운드)도 F1 0.662, E0 0.902, E1 0.312, E2 0.886(E2<F0 25/30, E2 vs E0 16/30)으로 같았다. class+load 의 shared 비율은 F0 23.8%(1,379/5,804), F1 87.4%(4,924/5,637), E0 24.1%, E1 96.6%(5,604/5,802), **E2 0%(0/5,667)** — 캐시가 버려지면 기본 CDS 도 같이 꺼진다.

**JDK 27 (e19)**: fat jar 캐시의 assembly 표에서 unregistered 가 0 이다(JDK 25 는 3,014). 앱 클래스 3,515개가 `Not loaded from "file:" code source`로 빠지고 운영 shared 는 2,221/5,902. 기동 비율은 F1 0.884, E1 0.333(15라운드). `AOTMode=required`는 `on`과 같은 결과(깨진 캐시 exit 1)이고 JDK 25·26 은 `Unrecognized value required for AOTMode`로 거부한다. JDK 27 기본 캐시는 `AOTMode=on`에서 20회 중 3회 `AOT Code Cache disabled: incompatible CompressedOops::base(): 0x0 vs current 0x…`로 기동에 실패했다. `-XX:+AOTCompatibleOopCompression`(diagnostic)으로 만든 캐시는 0/20, JDK 25·26 은 0/20 이다. auto 에서는 매번 캐시를 썼다.

**③ 무효화 (e13-d·e)**: 깨진 캐시에 빌드 게이트 명령을 돌리면 stdout 에 `[error  ][aot] unrecoverable error`(앞서 warning 이 찍혀 레벨 칸이 공백으로 채워진다), `Error occurred during initialization of VM`, `Unable to use AOT cache.`가 찍히고 exit 1, stderr 0줄이다. 크기·mtime 을 맞춘 `spring-core-7.0.9.jar`에서 `StringUtils.class`를 지우면 캐시 있는 기동은 `AOTMode=on`까지 exit 0 이고 `StringUtils`를 `shared objects file`에서 올린다. 캐시 없이 띄우면 `NoClassDefFoundError`로 exit 1 이다.

**③ 패치별 jar 검사 (e25, JDK-8377932)**: 25.0.0~25.0.3·26.0.0~26.0.1 은 앱 클래스패스 검사를 `need_to_check_app_classpath()`가 참일 때만 하는데, assembly(`AOTMode=create`)가 `_max_used_index`를 갱신하지 않아 create 중 앱 jar 에서 평소대로 load 된 클래스가 없으면 검사가 통째로 빠진다(수정 b41ba3a4, backport 25.0.4·26.0.2). 이 앱은 `Log4J2LoggingSystem`(training 에서 verification 실패로 빠진 클래스) 하나가 create 중 spring-boot jar 에서 load 돼 우연히 검사가 켜졌다. `-Dorg.springframework.boot.logging.LoggingSystem=…LogbackLoggingSystem` 으로 그 load 를 없애면 25.0.2·25.0.3 에서 touch 한 jar 도, 크기가 바뀐 spring-core 도 `on` exit 0 으로 통과한다. HelloWorld jar 는 만든 직후 mtime 을 과거로 고정한다(`st_mtime` 은 초 단위라 1초 안의 touch 는 mtime 이 같다).

**② training 범위 (e14, web 변형 앱, 15라운드, 캐시 없음 N 대비 비율 중앙값)**: onRefresh 캐시 A 는 기동 Started 0.414, 첫 200 까지 0.505 이지만 첫 요청 지연은 0.930(A<N 9/15). 요청 1,000회 + `jcmd AOT.end_recording` 캐시 B 는 첫 요청 0.334, 첫 200 까지 0.445(B<A 첫 요청 15/15). 첫 요청 동안 새로 load 되는 클래스는 N 730(shared 아님 718), A 676(전부 shared 아님), B 21. B 는 첫 요청 전에 이미 9,082개를 올려 둔다(N 8,059). B 에서 profile 재생만 끈 대조(`-XX:-AOTReplayTraining`)는 첫 요청 비율 0.970(6/10)으로 차이가 없다. 캐시 크기는 A 62,783,488 B, B 70,221,824 B. training·assembly 시간(부하 큰 상태, 한 번씩): A record 6,752 ms + create 2,378 ms, 한 줄 4,751 ms, B create 3,093 ms. 이 A 값은 e23 의 같은 라운드 재측정(3단계 2,043 ms, 한 줄 2,069 ms, load 4.4~5.5)으로 대체한다.

**training 을 끝내는 방법 (e14-train)**: 한 줄 방식(`AOTCacheOutput`) 실행 중 `jcmd <pid> AOT.end_recording`을 부르면 그 자리에서 자식 assembly 가 뜨고 캐시가 만들어진다. `HotSpotAOTCacheMXBean.endRecording()`도 같다(`{"mode":"record","wasRecording":true,"ended":true}`). record 후 SIGTERM 만으로 끝내도 configuration 이 남는다. `jcmd <pid> AOT.end_training`은 `Unknown diagnostic command`다. 바이너리로 보면 `AOT.end_recording`은 Temurin 25.0.3 에 있고 25.0.2 에 없다. `HotSpotAOTCacheMXBean`은 25.0.4.1 에 있고 25.0.3 에 없다(e17-B2).

**GC·헤더 (e15)**: G1 캐시는 Serial·Parallel·`-Xmx256m`·`-Xmx4g`·`G1HeapRegionSize=16m`·CPU 1개+메모리 512MB 흉내(Serial 선택) 모두에서 그대로 쓰인다. `-XX:+UseZGC`·`-XX:-UseCompressedOops`·`-Xmx40g`·`+UseCompactObjectHeaders`는 JDK 25·26 모두 거부된다(`The saved state of UseCompressedOops and UseCompressedClassPointers is different from runtime`). JDK 26 에서도 G1 으로 만든 캐시는 ZGC 에서 못 쓴다. ZGC 로 training 한 캐시는 JDK 26 에서 ZGC 로 5,640 shared(전부)이고 G1·Serial 에서는 거부된다. JDK 25 에서는 ZGC 캐시가 4,820/5,664 만 공유하고 `Using AOT-linked classes: false`다. `-XX:+AOTStreamableObjects`로 G1 에서 만든 캐시도 ZGC 에서는 거부된다(압축 포인터 상태가 다르다). 기준은 GC 종류가 아니라 `UseCompressedOops` 상태다.

**agent (e16)**: training 에 agent 없이 만든 캐시를 운영에서 `-javaagent`와 쓰면 세 JDK 모두 `[error][aot] Mismatched values for property jdk.module.addmods: java.instrument specified during runtime but not during dump time`, shared 0, `on` exit 1 이다. training 에도 같은 agent(B)거나 세 단계 `--add-modules=java.instrument`(C)면 쓰인다. 한 줄 방식은 `--add-modules`를 자식 JAVA_TOOL_OPTIONS 로 넘긴다. C 에서 클래스를 바꾸는 agent 는 premain 은 돌지만 `BlogApplication`을 바꾸지 못했다(캐시에서 premain 전에 올라왔다). B 에서는 training 이 그 클래스를 `From ClassFileLoadHook`으로 빼서 운영에서 바뀌었다. native agent 는 `can_generate_all_class_hook_events` 권한만으로는 캐시를 끄지 않고, ClassFileLoadHook 이벤트를 켜면 세 JDK 모두 `AOT cache has aot-linked classes. It cannot be used when JVMTI ClassFileLoadHook is in use.`로 끈다(auto exit 0·shared 0, on exit 1). dd-java-agent 를 붙인 기동(JDK 25, 10라운드, '캐시 없음 + dd' 대비 Started 비율): addmods 불일치로 버려진 캐시 1.124(기본 CDS 까지 꺼져 더 느리다), B 0.258, C 0.272. 벽시계는 B·C 모두 0.69 다(dd premain 이 Spring 시작 전에 돈다). e16 본 로그의 'Started 5회 중앙값'은 e17-F(Rosetta training)와 겹쳐 부하가 섞였으므로 `e16-rr-jdk25.log`를 쓴다.

**빌드 환경 (e17 로컬·e22)**: Microsoft 25.0.4.1 캐시는 같은 `25.0.4.1+1-LTS` 문자열의 Temurin 에서도 `The AOT cache was created by a different version or build of HotSpot`로 버려지고(shared 0), 25.0.3·25.0.2 도 같다. arm64 캐시를 x64(Rosetta) 에서 띄워도 같은 문구다. `cp -R`·`rsync -r`로 옮기면 mtime 이 바뀌어 `on` exit 1, `cp -Rp`·`rsync -a`는 exit 0. 한 줄 방식에 `-Xms512m -Xmx512m -XX:+AlwaysPreTouch`를 주면 자식이 같은 옵션을 받고, 두 JVM 이 1.57 초 함께 떠 합계 RSS 최대 1,160 MB(부모만 780 MB)였다. `app.url`이 없으면 training 은 #36102 와 같은 `Parameter specified as non-null is null`로 exit 1 이지만 **한 줄 방식은 그래도 캐시(45 MB)를 남기고 그 캐시는 운영에서 `on` exit 0 으로 쓰인다**. training 에만 `-Dapp.url` 또는 `JAVA_TOOL_OPTIONS`를 주고 운영에 다른 값을 줘도 캐시는 쓰이고 운영 값이 바인딩된다.

**CDS (e18·e20)**: `-XX:SharedArchiveFile`이나 `-Xshare:on|off`를 `-XX:AOTCache`와 함께 주면 JDK 25·26 모두 `Option AOTCache cannot be used at the same time with -Xshare:on, -Xshare:auto, -Xshare:off, -Xshare:dump, DumpLoadedClassList, SharedClassListFile, or SharedArchiveFile`로 VM 초기화가 실패한다(exit 1). JDK 21 은 AOT 플래그를 모두 `Unrecognized VM option`으로 거부하고, 펼친 배치 + 동적 CDS 는 fat jar 대비 0.469(15/15), 펼침만 대비 0.522 다.

**Proxy·multi-release (e19-d·e21)**: dynamic Proxy 는 JDK 25(42개)·26(43개)·27(43개) 모두 캐시에 실리지 않는다(`Unsupported location`, 25 는 warning, 26·27 은 info). `ClassFile*` 8개의 `cannot be archived` warning 은 JDK 27 에도 그대로다.

**캐시 밖 198줄 (e13-f)**: E1 의 class+load 5,802줄 중 캐시 밖 198줄은 다섯 묶음이다. [A] training 이 사유를 남기고 뺀 클래스 11개(`Log4J2LoggingSystem` Failed verification 1, multi-release `ClassFile*` 8, JFR event class 2)와 그 nest 의 lambda·TypeSwitch 15 = 26, [B] dynamic Proxy 42, [C] `__JVM_LookupDefineClass__`(LambdaForm$MH 등) 48, [D] onRefresh 종료 지점(`DefaultLifecycleProcessor.onRefresh()`의 `Runtime.halt(0)`, startBeans 전) 뒤에 처음 load 된 클래스 76(JDK 모듈 50 — Started 줄의 process uptime 을 재는 `ManagementFactory`·MXBean 계열 등, 앱 클래스패스 jar 5, lambda 21), [E] refresh 중 load 됐는데 캐시에 없는 lambda 6(원인 미특정). 1차의 onRefresh 없이 만든 캐시(e5)는 캐시 밖이 115줄(5,806 − 5,691)이었다.

## 재지 않은 것

- **docker (e17)**: 이 계정에서는 docker 소켓(`/Users/kibong/.docker/run/docker.sock`)이 다른 사용자 소유라 `permission denied`다. 이미지 레이어 크기(`docker history`), multi-stage `COPY --from`의 mtime 보존, 컨테이너 메모리 한도에서의 한 줄 방식 실패, buildx `--platform linux/amd64`(QEMU), `bootBuildImage`는 재지 않았다. 로컬로 바꿔 잴 수 있는 것만 `e17-local.sh`로 쟀다.
- **e15 컨테이너 한도**: docker 대신 `-XX:ActiveProcessorCount=1 -XX:MaxRAM=512m`로 ergonomics 를 흉내 냈다(Serial 이 골라진다). JDK 26 은 `MaxRAM` deprecated 경고를 stderr 에 1줄 찍는다.
- **e22 (c)**: `spring-boot-starter-data-jpa` 4.1.1 이 offline Gradle 캐시에 없어(3.0.4 만 있다) 이른 DB 접속 재현은 하지 않았다.
- **e14 의 연결 웜업·Tomcat 스레드 풀**: 이 변형 앱에는 외부 연결이 없다.
- **e25 의 JDK 26.0.0·26.0.1**: 로컬 바이너리가 없어 재지 않았다. 26 쪽 범위는 JBS backport(JDK-8379281, 26.0.2)로만 안다.

## 재현 명령

```bash
cd src/main/kotlin/com/petercoders/blog/aot
export J27=<Temurin 27 java>  J21=<Temurin 21 java>  JDKS=<Temurin 25 빌드들을 푼 폴더>
bash r2-pre.sh all                                   # 고정 jar 펼치기·캐시 6개·게이트
ROUNDS=30 bash e13.sh a; for s in b c d e; do bash e13.sh $s; done
bash variants/build-variant.sh e14-web prepare && bash variants/build-variant.sh e14-web build && bash variants/build-variant.sh e14-web extract
bash e14.sh prep && bash e14.sh train && bash e14.sh gate && ROUNDS=15 bash e14.sh measure && bash e14.sh diag && ROUNDS=10 bash e14.sh replay
bash e15.sh 25; bash e15.sh 26; bash e15.sh 25 extra; bash e15.sh 26 extra
bash e16.sh build; for v in 25 26 27; do bash e16.sh $v; done; bash e16-native.sh; bash e16-rr.sh 25
for s in B B2 F A C; do bash e17-local.sh $s; done
bash e18.sh; for s in a b c d e; do bash e19.sh $s; done
JDK_PATHS=<JDK 21 home> bash variants/build-variant.sh e20-jdk21 build; bash e20.sh prep; bash e20.sh a; bash e20.sh b
bash e21.sh; bash e13f.sh > runs/e13-f-nonshared-198.log   # e13-b 원본 class+load 와 e21 training 출력을 읽는다
bash e13g.sh > runs/e13-g-source-breakdown.log            # e3·e13-b 원본 class+load 의 source 별 내역
L=runs/e23-build-time.log; bash e23.sh header > $L; for i in $(seq 1 20); do bash e23.sh round $i >> $L; done; bash e23.sh summary $L >> $L
L=runs/e24-clinit-same-layout.log; bash e24.sh run 5 > $L; bash e24.sh summary $L >> $L
export JDKS=<Temurin 25.0.2·25.0.3·25.0.4.1 을 푼 폴더>; L=runs/e25-jar-check-by-patch.log; bash e25.sh header > $L
for j in T02 T03 T41 J25 J26; do bash e25.sh one $j >> $L; done; for j in T02 T03 T41 J25 J26; do bash e25.sh hello $j >> $L; done
for j in T03 T41; do bash e25.sh why $j >> $L; bash e25.sh whyq $j >> $L; done; for j in T02 T03 T41 J25 J26; do bash e25.sh quiet $j >> $L; done
bash variants/build-variant.sh e22-props prepare && bash variants/build-variant.sh e22-props build && bash variants/build-variant.sh e22-props extract && bash e22.sh prep && bash e22.sh run
```

`runs/work/`·`runs/work-r2/`·`runs/pinned/` 안의 `.aot`·`.jsa`·`.map`·jar 는 수십~수백 MB 라 커밋하지 않는다.
