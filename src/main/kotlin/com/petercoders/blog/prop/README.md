# prop — 설정값은 파일이 아니라 순서에서 나온다 (실험 코드)

이 디렉터리는 velog 글 [설정값은 파일이 아니라 순서에서 나온다](https://velog.io/@petercoders)(4편, 게시 후 링크로 교체)의 실측 코드다. 글 본문에는 결정적 출력 몇 줄만 실었고, 전체 코드와 로그는 여기에 있다.

## 실행 환경

| 항목 | 값 |
|---|---|
| CPU | Apple M5 Pro, 15코어 |
| 캐시라인 | `hw.cachelinesize=128` |
| OS | macOS (Darwin) |
| JVM (`/usr/bin/java`) | OpenJDK 26.0.2.1 (2026-08-18, build 26.0.2.1+1-7) |
| Gradle toolchain JDK | `JavaLanguageVersion.of(25)` — 별도 캐시된 JDK, Boot 부트 배너에는 "Java 25.0.4.1"(Microsoft build)로 표시됨 |
| Kotlin | 2.3.21 |
| Spring Boot | 4.1.1 (Spring Framework 7.0.9) |
| Gradle 실행 모드 | `--offline -q` |

절대 수치(실행 시간, 로그 용량 등)는 이 기기에 종속된다. 다른 기기로 옮길 수 있는 것은 소스 목록의 상대 순서·승자 판정 같은 비율·순서 관계뿐이다.

## 실험 목록

| 마커 | 무엇을 보나 | 글의 절 |
|---|---|---|
| P1 | 이 앱의 `PropertySource` 목록 전체 덤프, 0번/맨끝 칸, 서블릿 소스 존재 여부 | 목록의 0번 자리 |
| P2 | 프로파일 비활성/활성 시 새로 끼어드는 소스의 자리 비교 | ConfigData가 목록에 끼어드는 자리 |
| P3 | `spring.config.import`로 당겨온 문서와 그것을 선언한 문서의 상대 위치 | ConfigData가 목록에 끼어드는 자리 |
| P4 | 커맨드라인·환경변수·시스템프로퍼티·프로파일파일·import파일·defaultProperties 등 여러 소스에 같은 키를 두었을 때의 승자 | 같은 키를 일곱 곳에 둔 결과 |
| P5 | 파일 프로퍼티에서 kebab/camel/snake/upper 네 표기의 relaxed binding 성공 여부 | 같은 키라는 판정의 기준 |
| P6 | 환경 변수에서 같은 네 표기 + 레거시 후보 3종의 relaxed binding 성공 여부 | 같은 키라는 판정의 기준 |
| P7 | `@Value`와 `@ConfigurationProperties`가 같은 파일을 읽을 때 갈리는 지점 (미실행) | `@Value`와 `@ConfigurationProperties`가 갈리는 자리 |
| P8 | 기동 후 소스를 바꿨을 때 `Environment` 조회와 `@ConfigurationProperties` 빈 필드의 차이 (미실행) | `@Value`와 `@ConfigurationProperties`가 갈리는 자리 |

## 빌드·실행

파일은 `PropLab.kt` 하나이고, `main(args)`가 `args[0]`(`P1`~`P8`)로 해당 `runPn()`만 실행한다.

```bash
cd /Users/kibong_home/Desktop/development/blog
./gradlew --offline -q propLab --args='P1'
./gradlew --offline -q propLab --args='P2'
./gradlew --offline -q propLab --args='P3'
PROP_LAB_VALUE=fromOsEnv ./gradlew --offline -q propLab --args='P4'
./gradlew --offline -q propLab --args='P5'
./gradlew --offline -q propLab --args='P6'
./gradlew --offline -q propLab --args='P7'   # 아직 미실행
./gradlew --offline -q propLab --args='P8'   # 아직 미실행
```

공용 `src/main/resources/application.yaml`은 어떤 프로브도 건드리지 않는다. 실험용 yml은 각 프로브가 `java.io.tmpdir` 아래 매 실행 새 디렉터리에 직접 쓰고, `spring.config.additional-location`으로 읽어 들인다.

## `runs/` 로그 색인

| 파일 | 내용 |
|---|---|
| `runs/P1.log` | P1 전체 출력 |
| `runs/p2.log` | P2 전체 출력 |
| `runs/p3.log` | P3 전체 출력 |
| `runs/P4.log` | P4 전체 출력 |
| `runs/P5.log` | P5 전체 출력 |
| `runs/P6.log` | P6 전체 출력 |

`p2.log`/`p3.log`만 소문자인 것은 서로 다른 실행 라운드에서 tee 출력을 그대로 남긴 결과다. 게시 전에 대소문자 표기를 통일할 것. `runs/P7.log`, `runs/P8.log`는 아직 없다 — 실행되면 이 표와 위 실험 목록 표를 함께 갱신한다.

git add/commit/push는 이 저장소 관례상 사용자가 직접 한다.
