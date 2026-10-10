#!/usr/bin/env bash
# e17-local — e17(docker) 대신 로컬에서 확인할 수 있는 것만. 이 계정에서는 docker 데몬 소켓이 다른 사용자 소유라
# 접근이 거부된다(permission denied). 그래서 컨테이너 레이어 크기·COPY --from·buildx/QEMU·build 메모리 한도는 재지 않았다.
#   B   같은 JDK 25 의 다른 벤더·패치(Temurin 25.0.4.1 / 25.0.3 / 25.0.2)로 Microsoft 25.0.4.1 캐시를 띄운다
#   B2  Temurin 25.0.3 / 25.0.2 바이너리에 jcmd AOT.end_recording 과 HotSpotAOTCacheMXBean 이 있는가(버전 사실 확인)
#   F   아키텍처: Temurin 25.0.4.1 arm64 로 만든 캐시를 같은 버전 x64(Rosetta 2) 로 띄운다, 그 반대도. x64 training 시간
#   A   파일 복사가 mtime 을 지키는가: cp -R / cp -Rp / rsync -a 로 펼친 배치를 옮긴 뒤 같은 캐시로 띄운다
#   C   한 줄 방식의 메모리: -Xms512m -Xmx512m -XX:+AlwaysPreTouch 로 training 하며 java 프로세스 RSS 를 20ms 마다 잰다
# 사용: JDKS=<Temurin 들을 푼 폴더> e17-local.sh B|B2|F|A|C
source "$(dirname "$0")/r2-env.sh"
JDKS=${JDKS:?JDKS 필요}
T41=$(ls -d "$JDKS"/tem25041-arm/*/Contents/Home)/bin/java
T03=$(ls -d "$JDKS"/tem2503-arm/*/Contents/Home)/bin/java
T02=$(ls -d "$JDKS"/tem2502-arm/*/Contents/Home)/bin/java
TX=$(ls -d "$JDKS"/tem25041-x64/*/Contents/Home)/bin/java
A=$R2/dirA
APPO=(-jar application/$APPJAR)

case ${1:?B|B2|F|A|C} in
B)
  header e17-B "Microsoft 25.0.4.1 로 만든 캐시를 다른 벤더·패치의 JDK 25 로" "$J25" "$T41" "$T03" "$T02"
  for J in "$T41" "$T03" "$T02"; do
    v=$("$J" -version 2>&1 | sed -n 2p)
    (cd "$A"; "$J" -XX:AOTCache=app25.aot -Xlog:class+load:file=cl-e17b.log:none "${APPO[@]}" > out.txt 2> err.txt; rc=$?
     "$J" -XX:AOTCache=app25.aot -XX:AOTMode=on "${APPO[@]}" > out-on.txt 2>&1; rcon=$?
     echo "[$v] auto exit=$rc $(count_sources cl-e17b.log) on exit=$rcon"
     grep -E '\]\[aot' out.txt | sed -E 's/^\[[0-9.]+s\]/    auto:/' | head -3)
    (cd "$A"; "$J" -XX:AOTCache=app25.aot -Xlog:aot -XX:AOTMode=on "${APPO[@]}" 2>&1 | grep -iE 'version|build|jvm_ident|different' | head -3 | sed -E 's/^\[[0-9.]+s\]/    why:/')
  done
  ;;
B2)
  header e17-B2 "jcmd AOT.end_recording · HotSpotAOTCacheMXBean 이 들어온 패치 버전(바이너리로 확인)" "$T02" "$T03" "$T41" "$J25"
  for J in "$T02" "$T03" "$T41" "$J25"; do
    v=$("$J" -version 2>&1 | sed -n 2p)
    JH=${J%/bin/java}
    mx=$("$JH/bin/javap" jdk.management.HotSpotAOTCacheMXBean > /dev/null 2>&1 && echo 있음 || echo 없음)
    (cd "$A"; rm -f b2.aotconf; "$J" -XX:AOTMode=record -XX:AOTConfiguration=b2.aotconf -Dserver.port=0 "${APPO[@]}" > b2.out 2>&1 &
     pid=$!; perl -e 'select(undef,undef,undef,0.4)'
     help=$("$JH/bin/jcmd" $pid help 2>&1 | grep -oE 'AOT\.[a-z_]+' | tr '\n' ' ')
     wait $pid 2>/dev/null
     echo "[$v] jcmd help 의 AOT 명령: [${help:-없음}] / HotSpotAOTCacheMXBean: $mx")
  done
  echo "# record 실행은 앱이 스스로 끝나기 전(0.4초) 에 jcmd help 를 부른다. 앱이 먼저 끝나면 help 가 비어 보일 수 있어 다시 확인한다:"
  for J in "$T02" "$T03"; do
    JH=${J%/bin/java}
    (cd "$A"; rm -f b2.aotconf; "$J" -XX:AOTMode=record -XX:AOTConfiguration=b2.aotconf -Xlog:startuptime -XX:+UnlockDiagnosticVMOptions "${APPO[@]}" > /dev/null 2>&1 &
     pid=$!; out=""
     for _ in $(seq 1 40); do out=$("$JH/bin/jcmd" $pid help 2>/dev/null | grep -E 'AOT\.|VM\.version' | tr '\n' ' '); [ -n "$out" ] && break; perl -e 'select(undef,undef,undef,0.02)'; done
     wait $pid 2>/dev/null; echo "[$("$J" -version 2>&1 | sed -n 2p)] help(AOT·VM.version 줄): [$out]")
  done
  ;;
F)
  header e17-F "아키텍처가 다른 같은 JDK(Temurin 25.0.4.1 arm64 ↔ x64/Rosetta 2)" "$T41" "$TX"
  echo "# x64 java 의 아키텍처: $(file "$TX" | sed 's/.*: //')"
  (cd "$A"; rm -f tem-arm.aot; t0=$(now_ms); "$T41" -XX:AOTCacheOutput=tem-arm.aot -Dspring.context.exit=onRefresh "${APPO[@]}" > /dev/null 2>&1; t1=$(now_ms); echo "arm64 training+assembly exit=$? wall_ms=$((t1-t0)) size=$(stat -f %z tem-arm.aot)")
  (cd "$A"; rm -f tem-x64.aot; t0=$(now_ms); "$TX" -XX:AOTCacheOutput=tem-x64.aot -Dspring.context.exit=onRefresh "${APPO[@]}" > x64-train.out 2>&1; t1=$(now_ms); echo "x64(Rosetta) training+assembly exit=$? wall_ms=$((t1-t0)) size=$(stat -f %z tem-x64.aot 2>/dev/null)")
  for pair in "$T41:tem-arm.aot:arm64캐시→arm64" "$TX:tem-arm.aot:arm64캐시→x64" "$TX:tem-x64.aot:x64캐시→x64" "$T41:tem-x64.aot:x64캐시→arm64"; do
    J=${pair%%:*}; rest=${pair#*:}; c=${rest%%:*}; label=${rest#*:}
    (cd "$A"; "$J" -XX:AOTCache=$c -Xlog:class+load:file=cl-e17f.log:none "${APPO[@]}" > out.txt 2>&1; rc=$?
     "$J" -XX:AOTCache=$c -XX:AOTMode=on "${APPO[@]}" > out-on.txt 2>&1; rcon=$?
     echo "[$label] auto exit=$rc $(count_sources cl-e17f.log) on exit=$rcon $(grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' out.txt)"
     grep -E '\]\[aot' out.txt | sed -E 's/^\[[0-9.]+s\]/    auto:/' | head -3
     "$J" -XX:AOTCache=$c -Xlog:aot -XX:AOTMode=on "${APPO[@]}" 2>&1 | grep -iE 'version|build|different|arch|mismatch' | head -2 | sed -E 's/^\[[0-9.]+s\]/    why:/')
  done
  ;;
A)
  header e17-A "파일 복사 방식과 mtime(로컬 대응: docker COPY 는 재지 않았다)" "$J25"
  for how in "cp -R" "cp -Rp" "rsync -a" "rsync -r"; do
    dst=$R2/copy-test; rm -rf "$dst"; mkdir -p "$dst"
    $how "$A/application" "$dst/" ; cp -p "$A/app25.aot" "$dst/"
    (cd "$dst"; "$J25" -XX:AOTCache=app25.aot -XX:AOTMode=on "${APPO[@]}" > out.txt 2>&1; rc=$?
     echo "[$how] 앱 jar mtime=$(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' application/$APPJAR) lib mtime=$(stat -f '%Sm' -t '%Y-%m-%d' application/lib/spring-core-7.0.9.jar) → AOTMode=on exit=$rc $(grep -m1 -oE "(timestamp|size) has changed" out.txt)")
  done
  rm -rf "$R2/copy-test"
  ;;
C)
  header e17-C "한 줄 방식 training 의 메모리(부모 JVM + assembly 자식 JVM)" "$J25"
  cd "$A"; rm -f mem.aot
  python3 - "$J25" <<'PY'
import subprocess, sys, time
java = sys.argv[1]
cmd = [java, '-Xms512m', '-Xmx512m', '-XX:+AlwaysPreTouch', '-XX:AOTCacheOutput=mem.aot', '-Dspring.context.exit=onRefresh', '-jar', 'application/blog-0.0.1-SNAPSHOT.jar']
p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
peak_sum, peak_n, samples = 0, 0, []
def tree(pid):
    out = subprocess.run(['ps', '-Ao', 'pid=,ppid=,rss=,comm='], capture_output=True, text=True).stdout
    rows = [l.split(None, 3) for l in out.splitlines() if l.strip()]
    kids = {pid}
    changed = True
    while changed:
        changed = False
        for r in rows:
            if int(r[1]) in kids and int(r[0]) not in kids:
                kids.add(int(r[0])); changed = True
    return [(int(r[0]), int(r[2])) for r in rows if int(r[0]) in kids and r[3].endswith('java')]
t0 = time.time()
while p.poll() is None:
    t = tree(p.pid)
    s = sum(r for _, r in t)
    samples.append((time.time() - t0, len(t), s))
    if s > peak_sum: peak_sum = s
    if len(t) > peak_n: peak_n = len(t)
    time.sleep(0.02)
out = p.stdout.read()
both = [x for x in samples if x[1] >= 2]
print(f"exit={p.returncode} samples={len(samples)} max_java_processes={peak_n} peak_total_rss_mb={peak_sum/1024:.0f}")
if both:
    print(f"두 JVM 이 함께 떠 있던 구간: {both[0][0]:.2f}s ~ {both[-1][0]:.2f}s, 그 구간 최대 합계 RSS {max(x[2] for x in both)/1024:.0f} MB")
one = [x for x in samples if x[1] == 1]
if one:
    print(f"부모 하나만 있던 구간 최대 RSS {max(x[2] for x in one)/1024:.0f} MB")
for l in out.splitlines():
    if 'Picked up JAVA_TOOL_OPTIONS' in l or 'AOTCache creation' in l:
        print('    ' + l.replace('-Djava.class.path=application/blog-0.0.1-SNAPSHOT.jar ', '')[:220])
PY
  rm -f mem.aot
  ;;
esac
