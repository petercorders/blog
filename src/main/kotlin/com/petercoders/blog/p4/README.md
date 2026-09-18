# 실무에서 GC 다루기 — 실험 코드

velog 글 **실무에서 GC 다루기: 플래그보다 먼저 통과해야 하는 질문들**(4편)에서 쓴 코드다.
본문에는 요지만 남기고 전문과 실행 명령을 여기 둔다.

## 환경

| | |
|---|---|
| JDK | OpenJDK 25.0.4.1 |
| 기기 | Apple M5 Pro (15코어, 24GB, arm64) · macOS Darwin 25.6.0 |

절대값은 기기를 타므로 **비율만 이식 가능하다.** 벤치는 다른 작업이 없는 상태에서 돌린다
(load average 17.9 에서 다시 재니 처리량이 절반으로 떨어졌다).

## 파일

| 파일 | 무엇 |
|---|---|
| [`Bench.java`](Bench.java) | 라이브 셋 ~300MB 를 유지한 채 15초 동안 1KB 객체를 갈아 끼운다(단일 스레드) |
| [`BenchMT.java`](BenchMT.java) | 같은 일을 스레드 T개로. 코어를 채워 GC 스레드와 CPU를 경쟁시킨다 |
| [`gcpause.py`](gcpause.py) | `-Xlog:gc*` 로그에서 버킷별 pause 합계·비율을 뽑는다 |

## 실행

```bash
javac Bench.java BenchMT.java

# GC × 힙 조합 스윕 (본문 3절 표)
for heap in 1g 4g; do for gc in UseParallelGC UseG1GC UseZGC; do
  f=bench-15-$heap-$gc.log
  java -Xms$heap -Xmx$heap -XX:+$gc -Xlog:gc,gc+phases=info:file=$f:uptime BenchMT 15 | tail -1
  python3 - "$f" <<'PY'
import re,sys
t=n=0; m=0.0
for l in open(sys.argv[1]):
    x=re.search(r'Pause[^\n]*?(\d+\.\d+)ms\s*$', l.rstrip())
    if x: v=float(x.group(1)); t+=v; n+=1; m=max(m,v)
print(f'   pauses={n} total={t:.1f}ms max={m:.2f}ms ({t/150:.2f}% of 15s)')
PY
  echo "   fullgc=$(grep -c 'Pause Full' $f)"
done; done

# 단일 스레드 (표의 1 스레드 행)
for gc in UseParallelGC UseG1GC UseZGC; do
  java -Xms1g -Xmx1g -XX:+$gc -Xlog:gc,gc+phases=info:file=bench-single-1g-$gc.log:uptime Bench | tail -1
done

# 버킷별 pause 비율
python3 gcpause.py bench-single-1g-UseG1GC.log | head -5
```

## 본문이 인용하는 그 밖의 명령

```bash
java -Xms256m -Xmx256m -Xlog:safepoint Churn.java 2>&1 | grep -m3 'Safepoint "'
java -XX:+UseG1GC -XX:+PrintFlagsFinal -version | grep -E ' (UseCountedLoopSafepoints|LoopStripMiningIter) '
java -XX:ActiveProcessorCount=1 -Xlog:gc -version 2>&1 | head -1
java -XX:MaxRAM=2g -XX:+PrintFlagsFinal -version | grep ' MaxHeapSize '
java -Xms256m -Xmx256m '-Xlog:gc*:file=gclog/gc.log:uptime,level,tags:filecount=5,filesize=5k' Churn.java
jstat -gcutil <pid> 2000 3
```
