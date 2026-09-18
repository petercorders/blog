# tip — JVM 동시성 실무 (3편) 실험 코드

글: [JVM 동시성 실무: 무엇이 새는지는 코드가 정한다](https://velog.io/@petercoders/jvm-concurrency-practice) (게시 후 링크 갱신)

원고: `~/Desktop/blog/JAVA-KOTLIN-CONCURRENCY/3-CONCURRENCY-PRACTICE-POST.md`

이 디렉터리는 3편에서 새로 실행한 실험 5건의 소스와 로그만 담는다. 1·2편 실험(`jkc/`, `jvt/`, `kco/`)은 각자 디렉터리를 그대로 재사용한다.

## 실행 환경

| 항목 | 값 |
|---|---|
| CPU | Apple M5 Pro, 15코어 |
| 아키텍처 | arm64 |
| 캐시라인 | 128B |
| OS | Darwin (macOS) |
| JDK (본편) | OpenJDK 26.0.2.1 (build 26.0.2.1+1-7) |
| JDK (비교용, pinning 실험) | Eclipse Temurin 19.0.2+7 |
| kotlinc | 2.3.20 (IntelliJ IDEA 번들, JRE 26.0.2.1+1-7) |
| kotlinx-coroutines-core-jvm | 1.10.2 |

절대 시간(ms)·스레드 수는 이 기기를 탄다. 다른 기기로 옮길 때는 절대값이 아니라 갈림의 방향과 비율만 이식 가능하다.

## 실험 목록

| 파일 | 무엇을 보나 | 본문 절 |
|---|---|---|
| `BlockingWorkers.kt` | `Dispatchers.Default` 워커가 sleep·소켓 read·중첩 `runBlocking`에 막힐 때 스레드 상태·톱 프레임이 어떻게 갈리는지 | Default 워커를 잡는 세 줄 |
| `TimeoutSleep.kt` | `withTimeout(100)` 아래에서 그냥 sleep·`runInterruptible`+sleep·delay·플랫폼/가상 스레드의 소켓 read가 각각 언제 끝나는지 | cancel()이 못 끊는 블로킹과 runInterruptible |
| (소스 없음, JDK 설정·소스 grep) | `default.jfc`의 가상 스레드 이벤트 기본값과 `VirtualThreadSchedulerMXBean`/`JcmdVThreadCommands`/`VirtualThread`/`ThreadDumper` 소스 확인 | ThreadMXBean이 세지 않는 것 |
| `VtCount.java` | 가상 스레드 2,000개 위에서 `ThreadMXBean`·`VirtualThreadSchedulerMXBean`·`jcmd` 덤프·JFR이 각각 무엇을 세는지 | ThreadMXBean이 세지 않는 것 |
| `IoVsLoom.kt` | `Thread.sleep(100)` 1,000개를 `Dispatchers.IO`/`IO.limitedParallelism(1000)`/가상 스레드 디스패처에 던졌을 때 총 시간과 플랫폼 스레드 peak | 두 층을 겹쳐 쓰는 조합 |

## 빌드·실행 명령

환경 변수: `J26=$(/usr/libexec/java_home -v 26)`, `J19=$(/usr/libexec/java_home -v 19)`, `KC=kotlinc 절대경로`, `CO=kotlinx-coroutines-core-jvm-1.10.2.jar 절대경로`.

```bash
# BlockingWorkers.kt
"$KC" -cp "$CO" BlockingWorkers.kt -include-runtime -d bw.jar
$J26/bin/java -cp "bw.jar:$CO" BlockingWorkersKt > pid.txt & sleep 3; PID=$(head -1 pid.txt)
$J26/bin/jcmd $PID Thread.dump_to_file -format=json $PWD/bw.json

# TimeoutSleep.kt
"$KC" -cp "$CO" TimeoutSleep.kt -include-runtime -d ts.jar
for i in 1 2 3; do $J26/bin/java -cp "ts.jar:$CO" TimeoutSleepKt; done

# default.jfc / JDK 소스 확인 (소스 파일 없음, grep+unzip만)
grep -n -A4 -E 'name="jdk\.VirtualThread(Start|End|Pinned|SubmitFailed)"' "$J26/lib/jfr/default.jfc"
unzip -p "$J26/lib/src.zip" jdk.management/jdk/management/VirtualThreadSchedulerMXBean.java | grep -nE '@since|^public interface|^\s+(int|long|void) [a-zA-Z]+\('

# VtCount.java
$J26/bin/java -XX:StartFlightRecording=filename=vt.jfr,settings=default,jdk.VirtualThreadStart#enabled=true,jdk.VirtualThreadEnd#enabled=true VtCount.java > vt.txt & sleep 3
PID=$(sed -n 1p vt.txt)
$J26/bin/jcmd $PID Thread.dump_to_file -format=json $PWD/vt.json; grep -c '"virtual": true' vt.json
$J26/bin/jcmd $PID help | grep -iE 'vthread'; wait; cat vt.txt
$J26/bin/jfr summary vt.jfr | grep -E 'jdk.VirtualThread(Start|End|Pinned)'

# IoVsLoom.kt (케이스마다 JVM을 새로 띄운다 — keep-alive 워커가 다음 케이스의 peak를 오염시키지 않도록)
"$KC" -cp "$CO" IoVsLoom.kt -include-runtime -d ivl.jar
for case in IO LP1000 VT; do $J26/bin/java -cp "ivl.jar:$CO" IoVsLoomKt $case; done
```

## `runs/` 로그 색인

| 로그 | 대응 실험 |
|---|---|
| `runs/tip-p1-blocking-workers.log` | `BlockingWorkers.kt` |
| `runs/tip-p3-timeout-sleep.log` | `TimeoutSleep.kt` (3회 반복) |
| `runs/tip-p5-default-jfc.log` | `default.jfc`/JDK 소스 grep |
| `runs/tip-p7-vt-count.log` | `VtCount.java` |
| `runs/tip-p9-io-vs-loom.log` | `IoVsLoom.kt` (3회 반복) |

`git commit`/`git push`는 이 파이프라인이 하지 않는다. 파일만 놓아두고 사용자가 직접 커밋한다.
