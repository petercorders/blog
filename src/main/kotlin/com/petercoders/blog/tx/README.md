# @Transactional 프록시 실험 코드

velog 글 **"트랜잭션은 애노테이션이 아니라 프록시가 건다"** (SPRING-INTERNALS 시리즈 1편, 게시 후 링크로 교체)에서 쓴 실험 전부다.
본문에는 요지 코드와 결정적 출력 몇 줄만 남기고, 돌아가는 전체 코드와 원본 로그를 여기에 둔다.

절대 수치(스레드 이름, 힙 주소, 실행 시간 등)는 이 기기·이 실행에 종속된다. 이식 가능한 건 **비율과 분기 구조**뿐이다 — 다른 머신에서 돌리면 숫자는 달라져도 "CGLIB이냐 JDK냐", "match=true/false", "몇 행이 남았나(0/1/2)"의 상대적 결과는 재현되어야 한다.

## 환경

| | |
|---|---|
| CPU | Apple M5 Pro (15코어, arm64) |
| 캐시 라인 | 128B (`hw.cachelinesize`) |
| OS | macOS Darwin 25.6.0 |
| JDK (시스템 기본, `/usr/bin/java`) | OpenJDK 26.0.2.1 (2026-08-18) |
| JDK (Gradle 툴체인, 실제 실행) | Microsoft OpenJDK 25.0.4.1+1-LTS (`java.toolchain.languageVersion=25`) |
| Kotlin | 2.3.21 (Gradle) · kotlinc 2.3.21 (IntelliJ 번들, 단독 컴파일 비교용) |
| Spring Boot | 4.1.1 → Spring Framework 7.0.9 |
| DB | H2 인메모리 (`jdbc:h2:mem:txlab`) |

시스템 기본 `java -version`과 Gradle 툴체인이 실제로 띄우는 JDK가 다르다 — `java.version` 확인은 항상 둘 다 찍고, 실행 결과는 툴체인(25.0.4.1) 기준이다.

`build.gradle.kts`에 이 글을 위해 더한 것은 두 줄이다 — `spring-boot-starter-jdbc`, `com.h2database:h2`(runtimeOnly).
`TxLab.kt`의 `boot()` 프로퍼티에는 `spring.main.allow-circular-references=true`가 들어 있다. `BypassProbe`/`NestedProbe`가 자기 자신을 필드로 주입받는데(`self`), Spring Boot 4는 순환 참조를 기본 금지라 이 옵션 없이는 컨텍스트가 아예 안 뜬다.

## 파일

| 파일 | 무엇을 보나 | 본문 절 |
|---|---|---|
| [`Probes.kt`](Probes.kt) | `OrderService`/`PlainService`(프록시 종류), `BypassProbe`(미통과 경로·`final` 필드 NPE), `NestedProbe`(`REQUIRES_NEW`), `RollbackProbe`(롤백 규칙) 전체 빈 | 프록시의 생성 시점과 종류 결정 · 그 프록시를 통과하지 않는 호출 · all-open이 열어도 안 걸리는 곳 · 롤백 규칙 |
| [`TxLab.kt`](TxLab.kt) | 위 빈들을 부팅해 E1~E5 표로 찍는 러너(`main()`) | 전부 |
| [`AllOpenProbe.kt`](AllOpenProbe.kt) | all-open이 여는 것과 안 여는 것만 담은 최소 클래스(빈 아님) | Kotlin의 `final` 기본값과 그 경계 |

## 실행

```bash
cd ~/Desktop/development/blog
./gradlew --offline -q txLab
```

`TxLab.kt main()`이 표준출력에 `## [E1]`~`## [E5]` 순서로 전부 찍는다. 본문의 실험 id와 이 출력 구간의 대응:

| 본문 id | `TxLab.kt` 구간 | 내용 |
|---|---|---|
| e1 | `## [E1]` / `## [E1b]` | `proxy-target-class` 기본값/`false`일 때 프록시 종류 |
| e2 | `## [E2]` | 여섯 경로의 `isActualTransactionActive()` 매트릭스 |
| e4 | `## [E2b]` | `final` 메서드가 읽는 프록시 인스턴스의 빈 필드 |
| e5 | `## [E3]` | `REQUIRES_NEW` self-invocation vs self-field |
| e6 | `## [E4]` | `TransactionSynchronizationManager` 6칸 |
| e7 (미실행) | `## [E5]` | 롤백 규칙 4가지의 H2 행 수 |

all-open 바이트코드 비교(e3, 플러그인 끈 쪽은 `kotlinc`로 직접 컴파일한다):

```bash
KC="/Applications/IntelliJ IDEA.app/Contents/plugins/Kotlin/kotlinc"
CP=$(find ~/.gradle/caches -name 'spring-tx-7.0.9.jar' -o -name 'spring-core-7.0.9.jar' | tr '\n' ':')
"$KC/bin/kotlinc" -cp "$CP" -d /tmp/off src/main/kotlin/com/petercoders/blog/tx/AllOpenProbe.kt
javap -p -cp /tmp/off                 com.petercoders.blog.tx.AllOpenProbe   # 플러그인 OFF
javap -p -cp build/classes/kotlin/main com.petercoders.blog.tx.AllOpenProbe  # 플러그인 ON
```

## 로그 (`runs/`)

| 로그 | 대응 id | 내용 |
|---|---|---|
| `txlab.log` | 전체 | `./gradlew txLab` 전체 출력(E1~E5) — 병렬 세션들이 공유로 남긴 원본 |
| `e1.log` | e1 | 프록시 종류 표(E1/E1b) 전용 캡처 |
| `e2.log` | e2 | 미통과 경로 매트릭스(E2) 전용 캡처 |
| `e3.log` | e3 | `javap -p` OFF/ON 비교 전문 |
| `e4.log` | e4 | `final` 메서드 NPE(E2b) 전용 캡처 |
| `e5.log` | e5 | `REQUIRES_NEW` self vs proxy(E3) 전용 캡처 |
| `e6.log` | e6 | `TransactionSynchronizationManager` 슬롯(E4) 전용 캡처 |
| `allopen-javap.txt` | e3 | 플러그인 OFF/ON `javap -p` 원본(diff 대상) |

e7(롤백 규칙 4가지, `## [E5]`)은 이번 세션에서 전용 로그를 못 만들었다 — 본문에는 숫자를 넣지 않고 확인 포인트만 적었다.

## 커밋

이 폴더와 `build.gradle.kts` 변경은 커밋되지 않은 상태로 둔다. `git add`/`commit`/`push`는 사용자가 직접 한다.
