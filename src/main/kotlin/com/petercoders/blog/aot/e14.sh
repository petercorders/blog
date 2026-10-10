#!/usr/bin/env bash
# e14 — web starter 를 붙인 변형 앱(variants/e14-web)에서 training 범위가 첫 요청에 주는 차이(JDK 25.0.4.1).
#   캐시 A: onRefresh(공식 Dockerfile 방식) — record/create 3단계로 training·assembly 시간을 따로 잰다, 한 줄 방식 총 시간도
#   캐시 B: AOTMode=record 로 띄워 요청 1,000회 → jcmd AOT.end_recording → 종료 → AOTMode=create
#   변형 b' : AOTCacheOutput(한 줄) 상태에서 jcmd AOT.end_recording
#   변형 b'': AOTCacheOutput 상태에서 HotSpotAOTCacheMXBean.endRecording()(POST /api/aot/end)
#   변형 bt : record + 요청 1,000회 → 그냥 SIGTERM 으로 종료(jcmd 없이)
#   측정: N(캐시 없음)·A·B 라운드로빈, diag(첫 요청 중 class+load, 컴파일 수), B 의 -XX:-AOTReplayTraining 대조
# 사용: e14.sh prep|train|gate|measure|diag
source "$(dirname "$0")/r2-env.sh"
step=${1:?prep|train|gate|measure|diag}
W=$R2/web
DRV="python3 $AOT/e14_driver.py"
APPO=(-Dserver.port=0 -jar application/$APPJAR)
ROUNDS=${ROUNDS:-12}

prep() {
  rm -rf "$W"; mkdir -p "$W"
  cp -Rp /tmp/gradle-aot-e14-web/run/application "$W/"
  cp -p /tmp/gradle-aot-e14-web/run/blog-0.0.1-SNAPSHOT.jar "$W/web-fat.jar"
  echo "# 변형 jar sha256: $(shasum -a 256 "$W/web-fat.jar" | cut -c1-64) size=$(stat -f %z "$W/web-fat.jar")"
  echo "# lib $(ls "$W/application/lib" | wc -l | tr -d ' ')개: $(ls "$W/application/lib" | tr '\n' ' ')"
}

ms() { perl -MTime::HiRes=time -e 'printf "%d", time*1000'; }

train() {
  header e14-train "training 방식별 캐시 만들기와 training·assembly 시간" "$J25"
  cd "$W"
  rm -f web-*.aot web-*.aotconf
  echo "## (a) onRefresh — 3단계: record(onRefresh) → create"
  local t0 t1 t2
  t0=$(ms); "$J25" -XX:AOTMode=record -XX:AOTConfiguration=web-onrefresh.aotconf -Dspring.context.exit=onRefresh "${APPO[@]}" > a-record.log 2>&1; echo "record exit=$?"
  t1=$(ms); "$J25" -XX:AOTMode=create -XX:AOTConfiguration=web-onrefresh.aotconf -XX:AOTCache=web-onrefresh.aot "${APPO[@]}" > a-create.log 2>&1; echo "create exit=$?"
  t2=$(ms); echo "(a) training_ms=$((t1-t0)) assembly_ms=$((t2-t1)) cache=$(stat -f %z web-onrefresh.aot)"
  echo "## (a1) onRefresh — 한 줄(AOTCacheOutput) 총 시간"
  t0=$(ms); "$J25" -XX:AOTCacheOutput=web-onrefresh1.aot -Dspring.context.exit=onRefresh "${APPO[@]}" > a1.log 2>&1; echo "one-line exit=$?"
  t1=$(ms); echo "(a1) total_ms=$((t1-t0)) cache=$(stat -f %z web-onrefresh1.aot)"
  echo "## (b) record + 요청 1,000회 + jcmd AOT.end_recording → SIGTERM → create"
  $DRV --mode train --java "$J25" --cwd "$W" --label b-record --requests 1000 --end jcmd -- -XX:AOTMode=record -XX:AOTConfiguration=web-load.aotconf
  t1=$(ms); "$J25" -XX:AOTMode=create -XX:AOTConfiguration=web-load.aotconf -XX:AOTCache=web-load.aot "${APPO[@]}" > b-create.log 2>&1; echo "create exit=$?"
  t2=$(ms); echo "(b) assembly_ms=$((t2-t1)) aotconf=$(stat -f %z web-load.aotconf 2>/dev/null) cache=$(stat -f %z web-load.aot 2>/dev/null)"
  echo "## (bt) record + 요청 1,000회 → SIGTERM 만(jcmd 없이) → create"
  $DRV --mode train --java "$J25" --cwd "$W" --label bt-record --requests 1000 --end term -- -XX:AOTMode=record -XX:AOTConfiguration=web-term.aotconf
  "$J25" -XX:AOTMode=create -XX:AOTConfiguration=web-term.aotconf -XX:AOTCache=web-term.aot "${APPO[@]}" > bt-create.log 2>&1; echo "create exit=$? aotconf=$(stat -f %z web-term.aotconf 2>/dev/null) cache=$(stat -f %z web-term.aot 2>/dev/null)"
  echo "## (b') AOTCacheOutput(한 줄) + 요청 1,000회 + jcmd AOT.end_recording"
  $DRV --mode train --java "$J25" --cwd "$W" --label b1-oneline-jcmd --requests 1000 --end jcmd -- -XX:AOTCacheOutput=web-load1.aot
  echo "(b') cache=$(stat -f %z web-load1.aot 2>/dev/null || echo 없음)"
  echo "## (b'') AOTCacheOutput(한 줄) + 요청 1,000회 + HotSpotAOTCacheMXBean.endRecording()"
  $DRV --mode train --java "$J25" --cwd "$W" --label b2-oneline-mx --requests 1000 --end mx -- -XX:AOTCacheOutput=web-mx.aot
  echo "(b'') cache=$(stat -f %z web-mx.aot 2>/dev/null || echo 없음)"
  echo "## (x) Microsoft 25.0.0 이 넣었던 이름 AOT.end_training 은 지금 빌드에서 받아들여지는가"
  "$J25" -XX:AOTMode=record -XX:AOTConfiguration=web-x.aotconf "${APPO[@]}" > x.log 2>&1 &
  local pid=$!
  for _ in $(seq 1 200); do grep -q 'Tomcat started on port' x.log && break; perl -e 'select(undef,undef,undef,0.1)'; done
  "${J25%/java}/jcmd" $pid help 2>&1 | grep -E 'AOT\.' | sed 's/^/    jcmd help: /'
  "${J25%/java}/jcmd" $pid AOT.end_training 2>&1 | sed 's/^/    AOT.end_training: /'
  kill $pid; wait $pid 2>/dev/null
  rm -f web-x.aotconf
  ls -l web-*.aot web-*.aotconf
}

gate_all() {
  header e14-gate "만든 캐시마다 AOTMode=on 게이트" "$J25"
  cd "$W"
  for c in web-onrefresh.aot web-onrefresh1.aot web-load.aot web-term.aot web-load1.aot web-mx.aot; do
    [ -f "$c" ] || { echo "# $c 없음"; continue; }
    gate "$c" "$J25" "$c" -Dspring.context.exit=onRefresh "${APPO[@]}"
  done
}

opts() {
  case $1 in
    N) echo "";;
    A) echo "-XX:AOTCache=web-onrefresh.aot";;
    B) echo "-XX:AOTCache=web-load.aot";;
    Bnr) echo "-XX:AOTCache=web-load.aot -XX:+UnlockDiagnosticVMOptions -XX:-AOTReplayTraining";;
  esac
}

measure_runs() {
  header e14-measure "N/A/B 라운드로빈 ${ROUNDS}회 — 첫 200 까지 시간·첫 요청·첫 100요청 p50/p99" "$J25"
  local conds=(N A B)
  for r in $(seq 1 "$ROUNDS"); do
    echo "## round $r uptime: $(uptime | sed 's/.*load averages*: //')"
    local off=$(( (r-1) % 3 ))
    for k in 0 1 2; do
      local c=${conds[$(( (k+off) % 3 ))]}
      $DRV --mode measure --java "$J25" --cwd "$W" --label "$c r=$r" -- $(opts $c)
    done
  done
}

summarize_measure() {
  python3 - "$1" <<'PY'
import re, sys, statistics as st
rows = {}
for l in open(sys.argv[1]):
    m = re.match(r'^(N|A|B) r=(\d+) port_seen_ms=(\d+) first200_ms=(\d+) first_req_ms=([0-9.]+) p50_ms=([0-9.]+) p99_ms=([0-9.]+) n=\d+ started_s=([0-9.na]+) process_s=([0-9.na]+)', l)
    if m:
        rows.setdefault(m.group(1), {})[int(m.group(2))] = [float(x) for x in m.groups()[2:]]
print('## 요약 — 중앙값, 그리고 같은 라운드 N 대비 비율의 중앙값')
keys = ['port_seen_ms', 'first200_ms', 'first_req_ms', 'p50_ms', 'p99_ms', 'started_s', 'process_s']
for c in ['N', 'A', 'B']:
    d = rows.get(c, {})
    meds = {k: st.median([v[i] for v in d.values()]) for i, k in enumerate(keys)}
    ratios = {k: st.median([d[r][i] / rows['N'][r][i] for r in d if r in rows['N']]) for i, k in enumerate(keys)}
    print(f"{c} n={len(d)} " + ' '.join(f"{k}={meds[k]:.3f}" if k.endswith('_s') else f"{k}={meds[k]:.1f}" for k in keys))
    print(f"{c} ratio/N " + ' '.join(f"{k}={ratios[k]:.3f}" for k in keys))
for a, b, k in [('B', 'A', 2), ('A', 'N', 2), ('B', 'N', 2), ('B', 'A', 1), ('A', 'N', 1)]:
    common = [r for r in rows[a] if r in rows[b]]
    wins = sum(1 for r in common if rows[a][r][k] < rows[b][r][k])
    print(f"sign: {a} < {b} ({keys[k]}) in {wins}/{len(common)} rounds")
PY
}

diag() {
  header e14-diag "첫 요청 동안 load 되는 클래스와 컴파일 수(조건마다 ${DIAG_N:-2}회)" "$J25"
  cd "$W"
  for i in $(seq 1 "${DIAG_N:-2}"); do
    for c in N A B Bnr; do
      $DRV --mode diag --java "$J25" --cwd "$W" --label "$c try=$i" --classload "$W/cl-diag-$c.log" -- $(opts $c)
    done
  done
  echo "## 첫 요청 동안 shared 가 아닌 class+load 줄의 출처(조건 B, 마지막 try 기준 패키지 상위 10)"
  echo "# (diag 파일은 전체 기동분이라 여기서는 B 와 A 의 nonshared 전체 패키지 분포만 비교한다)"
  for c in A B; do
    echo "### $c nonshared 패키지 상위"
    grep -v 'shared objects file' "$W/cl-diag-$c.log" | awk '{print $1}' | sed -E 's/\.[^.]+$//' | sort | uniq -c | sort -rn | head -10 | sed 's/^/    /'
  done
}

replay() {
  header e14-replay "B 캐시에서 profile 재생(AOTReplayTraining)만 끈 대조 — 라운드로빈 ${ROUNDS}회" "$J25"
  local conds=(B Bnr)
  for r in $(seq 1 "$ROUNDS"); do
    echo "## round $r uptime: $(uptime | sed 's/.*load averages*: //')"
    local off=$(( (r-1) % 2 ))
    for k in 0 1; do
      local c=${conds[$(( (k+off) % 2 ))]}
      $DRV --mode measure --java "$J25" --cwd "$W" --label "$c r=$r" -- $(opts $c)
    done
  done
  python3 - "$RUNS/e14-replay.log" <<'PY'
import re, sys, statistics as st
rows = {}
for l in open(sys.argv[1]):
    m = re.match(r'^(B|Bnr) r=(\d+) port_seen_ms=(\d+) first200_ms=(\d+) first_req_ms=([0-9.]+) p50_ms=([0-9.]+)', l)
    if m: rows.setdefault(m.group(1), {})[int(m.group(2))] = (float(m.group(3)), float(m.group(4)), float(m.group(5)), float(m.group(6)))
for c in ['B', 'Bnr']:
    d = rows[c]
    print(f"{c} n={len(d)} port_seen_ms={st.median([v[0] for v in d.values()]):.0f} first200_ms={st.median([v[1] for v in d.values()]):.0f} first_req_ms={st.median([v[2] for v in d.values()]):.1f} p50_ms={st.median([v[3] for v in d.values()]):.2f}")
common = [r for r in rows['B'] if r in rows['Bnr']]
print(f"sign: B < Bnr (first_req_ms) in {sum(1 for r in common if rows['B'][r][2] < rows['Bnr'][r][2])}/{len(common)} rounds")
print(f"ratio B/Bnr first_req (round median) = {st.median([rows['B'][r][2] / rows['Bnr'][r][2] for r in common]):.3f}")
PY
}

case $step in
  replay) replay > "$RUNS/e14-replay.log" 2>&1; tail -5 "$RUNS/e14-replay.log" ;;
  prep) prep ;;
  train) train > "$RUNS/e14-train.log" 2>&1; grep -v '^#   ' "$RUNS/e14-train.log" | cut -c1-260 ;;
  gate) gate_all > "$RUNS/e14-gate.log" 2>&1; cat "$RUNS/e14-gate.log" | grep -v '^#   ' ;;
  measure) measure_runs > "$RUNS/e14-measure.log" 2>&1; summarize_measure "$RUNS/e14-measure.log" >> "$RUNS/e14-measure.log"; tail -14 "$RUNS/e14-measure.log" ;;
  diag) diag > "$RUNS/e14-diag.log" 2>&1; grep -v '^#   ' "$RUNS/e14-diag.log" | cut -c1-300 ;;
esac
