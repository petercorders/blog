# GC 내부 구조 실험 코드

velog 글 **[GC 내부 구조: 카드 테이블부터 ZGC까지](https://velog.io/@petercoders)** 2편에서 쓴 실험들이다.
본문에는 핵심 몇 줄만 남기고, 돌아가는 전체 코드와 원본 로그를 여기에 둔다.

## 환경

전부 이 조건에서 실측했다. 절대값은 기기를 타니 **비율만 보면 된다.**

| | |
|---|---|
| CPU | Apple M5 Pro (15코어, arm64), 메모리 24GB |
| OS | macOS Darwin 25.6.0 |
| 캐시라인 | 128B (x86 은 64B) |
| JDK | Microsoft OpenJDK **25.0.4.1** LTS — 본문 기준 |
| JDK (비교) | OpenJDK **26.0.2.1** — JEP 522 전후를 비교하는 곳만 |
| 코틀린 | kotlinc 2.3.20 |

## 실험 목록

| 파일 | 무엇을 보나 | 본문 절 |
|---|---|---|
| [`RemSetCoarsen.kt`](RemSetCoarsen.kt) | 리멤버드 셋 카드 집합이 InlinePtr → Array Of Cards → Howl → Full 로 갈아타는 순간 | 리멤버드 셋 |
| [`SatbDemo.kt`](SatbDemo.kt) | SATB 프리라이트 배리어가 도는 동안의 동시 마킹 사이클 | SATB |
| [`CardFalseSharing.kt`](CardFalseSharing.kt) | 자바 코드에 공유 상태가 없는데 카드 테이블이 공유 상태가 되는 것 | 카드 테이블 |
| [`StackRoots.kt`](StackRoots.kt) | 스택 스캔 비용이 스레드 수를 따라가는 모습 (G1 vs ZGC) | 스택 워터마크 |
| [`ZColorBits.kt`](ZColorBits.kt) | ZGC 컬러 포인터 하위 16비트가 사이클마다 바뀌는 것 | 컬러 포인터 |

## 빌드와 실행

각 파일은 자기 `main` 을 갖는 독립 실행 파일이다. `kotlinc -include-runtime` 이 `Main-Class` 를
매니페스트에 넣어 주므로 `java -jar` 로 바로 돌아간다.

```bash
kotlinc RemSetCoarsen.kt -include-runtime -d rem.jar
java -Xms1g -Xmx1g -XX:+UseG1GC \
  -Xlog:gc,gc+init,gc+phases=debug,gc+remset=debug,gc+refine=debug \
  -jar rem.jar 2000000 100000
```

```bash
kotlinc SatbDemo.kt -include-runtime -d satb.jar
java -Xmx512m -XX:+ExplicitGCInvokesConcurrent \
  -Xlog:gc,gc+phases=debug,gc+marking=debug -jar satb.jar
```

```bash
kotlinc CardFalseSharing.kt -include-runtime -d fs.jar
for pad in 0 1024 65536; do
  java -XX:+UseSerialGC                    -Xmn256m -jar fs.jar $pad
  java -XX:+UseSerialGC -XX:+UseCondCardMark -Xmn256m -jar fs.jar $pad
done
```

```bash
kotlinc StackRoots.kt -include-runtime -d stack.jar
for n in 50 500 2000; do
  java -Xmx1g -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent \
    -Xlog:gc,gc+phases=trace,safepoint -jar stack.jar $n
  java -Xmx1g -XX:+UseZGC -Xlog:gc,gc+phases=debug,safepoint -jar stack.jar $n
done
```

```bash
kotlinc ZColorBits.kt -include-runtime -d z.jar
java --sun-misc-unsafe-memory-access=allow -XX:+UseZGC -Xlog:gc -jar z.jar
```

`ZColorBits` 는 로드 배리어를 피해 참조 필드를 정수로 읽어야 해서 `sun.misc.Unsafe` 를 쓴다.
JDK 24(JEP 498)부터 경고가 뜨므로 `--sun-misc-unsafe-memory-access=allow` 가 필요하다.

## 실행 로그

[`runs/`](runs) 에 위 명령을 그대로 돌린 원본 출력이 들어 있다. 각 파일 머리에 JDK 버전·기기·명령이 적혀 있다.

| 로그 | 내용 |
|---|---|
| [`remset-coarsen-jdk25.log`](runs/remset-coarsen-jdk25.log) | coarsening 카운터와 Scan Heap Roots 시간 (75KB) |
| [`remset-coarsen-jdk26.log`](runs/remset-coarsen-jdk26.log) | 같은 실험, JEP 522 적용된 JDK 26 |
| [`satb-jdk25.log`](runs/satb-jdk25.log) | 동시 마킹 사이클 전체 (84KB) |
| [`false-sharing-jdk25.txt`](runs/false-sharing-jdk25.txt) | 패딩 3종 × `UseCondCardMark` 2종, 각 3라운드 |
| [`stackroots-jdk25.txt`](runs/stackroots-jdk25.txt) | 스레드 50/500/2000, G1 과 ZGC |
| [`zcolorbits-jdk25.txt`](runs/zcolorbits-jdk25.txt) | GC 3사이클 동안의 포인터 비트 |
| [`jep522-jdk25-vs-jdk26.txt`](runs/jep522-jdk25-vs-jdk26.txt) | 사라진 플래그, 새 플래그, 리파인 로그 형식 차이 |

## 스프링 부트 애플리케이션과의 관계

없다. 이 디렉터리의 파일들은 각자 `main` 을 가진 독립 실험이라 부트 진입점 탐색을 방해한다.
그래서 `build.gradle.kts` 에 진입점을 못박아 두었다.

```kotlin
springBoot {
    mainClass.set("com.petercoders.blog.BlogApplicationKt")
}
```
