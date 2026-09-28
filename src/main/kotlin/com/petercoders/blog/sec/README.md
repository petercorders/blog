# 메서드 시큐리티 프록시 실험 코드

velog 글 **"권한 검사가 걸렸는데도 행이 남는다"** (SPRING-INTERNALS 시리즈 2편, 게시 후 링크로 교체)에서 쓴 실험 전부다.
본문에는 요지 코드와 결정적 출력 몇 줄만 남기고, 돌아가는 전체 코드와 원본 로그를 여기에 둔다.

절대 수치(행 수, 클래스 이름의 해시 접미사, 실행 시간 등)는 이 기기·이 실행에 종속된다. 이식 가능한 건 **비율과 방향**뿐이다 — 다른 머신에서 돌리면 숫자는 달라져도 "걸리느냐/안 걸리느냐", "바깥이냐 안쪽이냐", "order 값의 대소 관계"의 상대적 결과는 재현되어야 한다.

## 환경

| | |
|---|---|
| CPU | Apple M5 Pro (15코어, arm64) |
| 캐시 라인 | 128B (`hw.cachelinesize`) |
| OS | macOS Darwin 25.6.0 |
| JDK (시스템 기본, `/usr/bin/java`) | OpenJDK 26.0.2.1 (2026-08-18) |
| JDK (Gradle 툴체인 지정) | `JavaLanguageVersion.of(25)` — 이 기기에서는 활성 JVM이 26.0.2.1로 실측됨(과제 문서가 전제한 25와 다르다) |
| Kotlin | 2.3.21 |
| Gradle | 9.7.1 |
| Spring Boot | 4.1.1 → Spring Framework 7.0.9 / Spring Security 7.1.1 |
| DB | H2 인메모리 (`jdbc:h2:mem:seclab*`, 실험마다 별 스키마) |

`build.gradle.kts`에 이 글을 위해 더한 것은 `spring-boot-starter-security` 한 줄이다(웹 스타터는 넣지 않았다 — `SecurityContextHolder`에 `Authentication`을 직접 넣어 HTTP 없이 인가만 잰다).
`SecLab.kt`의 `boot()` 프로퍼티에는 1편과 같은 이유로 `spring.main.allow-circular-references=true`가 들어 있다 — `SecureProbe`가 자기 자신을 필드로 주입받는데(`self`), Spring Boot 4는 순환 참조를 기본 금지라 이 옵션 없이는 컨텍스트가 안 뜬다.

## 실험 목록

| 파일 | 무엇을 보나 | 본문 절 |
|---|---|---|
| [`SecLab.kt`](SecLab.kt) | 러너 `main()`. 인자로 받은 섹션 마커(S1~S6)에 맞는 `runS*()`를 부른다. `EvalCounter`/`FilterProbe` 등 공용 프로브도 여기 있다 | 전부 |
| [`SecProbes.kt`](SecProbes.kt) | 공용 빈 정의(`EvalCounter` 등 여러 절에서 재사용하는 것들) | 시큐리티 인터셉터가 올라가는 자리 · SpEL은 몇 번 평가되는가 |
| [`SecProbesS2.kt`](SecProbesS2.kt) | `SecureProbe` — 1편 `BypassProbe`와 동형인 여덟 경로(생성자·`@PostConstruct`·외부호출·`this.`·`self`·`private`·`protected`·`final`) | 여덟 경로, 같은 결과 반대의 의미 |
| [`SecProbesS3.kt`](SecProbesS3.kt) | `TxOrderProbe` — `@PreAuthorize` SpEL 안에서 `isActualTransactionActive()`를 찍어 진입 순서를 "결과"로 판정 | 무엇이 바깥인가(어드바이저 order) |
| [`SecProbesS4.kt`](SecProbesS4.kt) | `OrderProbe.withdraw()` — `@Transactional` + `@PostAuthorize`, 통과/거부 두 케이스(S4)와 트랜잭션 어드바이저를 바깥으로 뒤집은 대조(S4b) | 무엇이 바깥인가(커밋 전/후 거부) |
| [`SecConfig.kt`](SecConfig.kt) | `TxOuterConfig` — `@EnableTransactionManagement(order = 0, proxyTargetClass = true)`, S4b 전용 프로필(`txouter`) 설정 | 무엇이 바깥인가(S4b) |

미실행(로컬에서 5초 안에 돈다, 이번 세션에서는 안 돌림): S5b(Kotlin `listOf()` + `@PostFilter`, 6.3 이하/6.4 이상 비교), S6(`@HandleAuthorizationDenied` 대체값 반환).

## 실행

```bash
cd ~/Desktop/development/blog
./gradlew -q secLab --args='S1'    # 최초 1회는 spring-security 의존을 받아야 해서 --offline 을 못 쓴다
./gradlew --offline -q secLab --args='S2'
./gradlew --offline -q secLab --args='S3'
./gradlew --offline -q secLab --args='S4'
./gradlew --offline -q secLab --args='S4b'
./gradlew --offline -q secLab --args='S5'
```

`--args`의 마커 하나가 `SecLab.kt main()`의 `when` 분기 하나에 대응한다. 마커 → 본문 절:

| 마커 | 본문에서 다루는 것 |
|---|---|
| `S1` | 어드바이저 빈 전체 목록·클래스·order, `internalAutoProxyCreator` 실제 클래스 |
| `S2` | 여덟 경로 × admin/user 판정 매트릭스 |
| `S3` | 어드바이저 order 재확인 + `@PreAuthorize` 평가 시점의 트랜잭션 활성 여부 |
| `S4` | `@Transactional`+`@PostAuthorize` 기본 order에서 거부 후 행 잔존 |
| `S4b` | 트랜잭션 어드바이저 order=0(바깥)일 때 같은 거부가 롤백되는지 |
| `S5` | `@PreAuthorize`(호출당 1회) vs `@PostFilter`(원소 수만큼) SpEL 평가 횟수 |

## 로그 (`runs/`)

| 로그 | 대응 마커 |
|---|---|
| `s1.log` | S1 |
| `s2.log` | S2 |
| `s3.log` | S3 |
| `s4.log` | S4 |
| `s4b.log` | S4b |
| `s5.log` | S5 |

`s5b.log`·`s6.log`는 없다(S5b·S6 미실행). 돌리면 본문에 적힌 확인 포인트(6.3 이하는 `UnsupportedOperationException`, 6.4 이상은 예외 없이 줄어든 새 리스트 / 거부 예외가 `AuthorizationDeniedException`이자 `AccessDeniedException`으로 잡히는지)를 로그에서 그대로 볼 수 있어야 한다.

## 커밋

이 폴더와 `build.gradle.kts` 변경은 커밋되지 않은 상태로 둔다. `git add`/`commit`/`push`는 사용자가 직접 한다.
