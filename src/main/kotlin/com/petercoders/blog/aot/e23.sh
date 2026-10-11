#!/usr/bin/env bash
# e23 — onRefresh 캐시를 만드는 두 방식의 빌드 시간을 같은 라운드에서 번갈아 잰다(web 변형 앱, JDK 25.0.4.1).
#   3단계 : -XX:AOTMode=record(onRefresh) → -XX:AOTMode=create   (training_ms, assembly_ms, 합 total_ms)
#   한 줄 : -XX:AOTCacheOutput(onRefresh)                       (total_ms, 부모 training + 자식 assembly)
#   e14-train 의 (a)·(a1)은 부하(load 16~31) 아래 한 번씩 잰 값이라 한 줄 방식이 3단계 합보다 짧게 나왔다.
#   그 차이가 방식 때문인지 부하 때문인지 가르려고 같은 라운드 안에서 순서를 번갈아 다시 잰다.
#   홀수 라운드는 3단계 먼저, 짝수 라운드는 한 줄 먼저 돈다. 캐시는 $W/e23/ 에만 만든다(e14 캐시는 건드리지 않는다).
# 사용: e23.sh header | e23.sh round <i> | e23.sh summary <log>
source "$(dirname "$0")/r2-env.sh"
step=${1:?header|round|summary}
W=$R2/web
O=$W/e23
APPO=(-Dserver.port=0 -Dspring.context.exit=onRefresh -jar application/$APPJAR)

three_step() {
  local i=$1 t0 t1 t2 rc1 rc2
  rm -f "$O"/s3-$i.aot "$O"/s3-$i.aotconf
  t0=$(now_ms); "$J25" -XX:AOTMode=record -XX:AOTConfiguration="$O"/s3-$i.aotconf "${APPO[@]}" > "$O"/s3-$i-record.log 2>&1; rc1=$?
  t1=$(now_ms); "$J25" -XX:AOTMode=create -XX:AOTConfiguration="$O"/s3-$i.aotconf -XX:AOTCache="$O"/s3-$i.aot "${APPO[@]}" > "$O"/s3-$i-create.log 2>&1; rc2=$?
  t2=$(now_ms)
  echo "r=$i mode=3step training_ms=$((t1-t0)) assembly_ms=$((t2-t1)) total_ms=$((t2-t0)) record_exit=$rc1 create_exit=$rc2 cache=$(stat -f %z "$O"/s3-$i.aot 2>/dev/null || echo NA) load1=$(uptime | sed -E 's/.*load averages?: ([0-9.]+).*/\1/')"
}

one_line() {
  local i=$1 t0 t1 rc
  rm -f "$O"/s1-$i.aot "$O"/s1-$i.aot.config
  t0=$(now_ms); "$J25" -XX:AOTCacheOutput="$O"/s1-$i.aot "${APPO[@]}" > "$O"/s1-$i.log 2>&1; rc=$?
  t1=$(now_ms)
  echo "r=$i mode=oneline total_ms=$((t1-t0)) exit=$rc cache=$(stat -f %z "$O"/s1-$i.aot 2>/dev/null || echo NA) child=$(grep -c 'Launching child process' "$O"/s1-$i.log) load1=$(uptime | sed -E 's/.*load averages?: ([0-9.]+).*/\1/')"
}

case $step in
  header)
    header e23-build-time "onRefresh 캐시 빌드 시간: 3단계(record→create) vs 한 줄(AOTCacheOutput), 같은 라운드 번갈아" "$J25"
    echo "# app: web 변형(e14), $W/application/$APPJAR sha256 $(shasum -a 256 "$W/application/$APPJAR" | cut -c1-64)"
    echo "# 출력: $O (홀수 라운드 3단계 먼저, 짝수 라운드 한 줄 먼저)"
    mkdir -p "$O"
    ;;
  round)
    i=${2:?round number}
    cd "$W"
    if (( i % 2 == 1 )); then three_step "$i"; one_line "$i"; else one_line "$i"; three_step "$i"; fi
    ;;
  summary)
    log=${2:?log}
    echo "## summary"
    echo "rounds=$(grep -c 'mode=3step' "$log")"
    echo "3step training_ms median=$(grep 'mode=3step' "$log" | sed -E 's/.*training_ms=([0-9]+).*/\1/' | median)"
    echo "3step assembly_ms median=$(grep 'mode=3step' "$log" | sed -E 's/.*assembly_ms=([0-9]+).*/\1/' | median)"
    echo "3step total_ms median=$(grep 'mode=3step' "$log" | sed -E 's/.*total_ms=([0-9]+).*/\1/' | median)"
    echo "oneline total_ms median=$(grep 'mode=oneline' "$log" | sed -E 's/.*total_ms=([0-9]+).*/\1/' | median)"
    # 같은 라운드 비율(한 줄 / 3단계 합)과 한 줄이 더 짧았던 라운드 수
    paste <(grep 'mode=3step' "$log" | sed -E 's/^r=([0-9]+).*total_ms=([0-9]+).*/\1 \2/' | sort -n) \
          <(grep 'mode=oneline' "$log" | sed -E 's/^r=([0-9]+).*total_ms=([0-9]+).*/\1 \2/' | sort -n) |
      awk '{r=$4/$2; printf "r=%s ratio_oneline_over_3step=%.3f\n", $1, r; if ($4<$2) w++} END{printf "oneline_shorter_rounds=%d/%d\n", w, NR}'
    echo "ratio median=$(paste <(grep 'mode=3step' "$log" | sed -E 's/^r=([0-9]+).*total_ms=([0-9]+).*/\1 \2/' | sort -n) <(grep 'mode=oneline' "$log" | sed -E 's/^r=([0-9]+).*total_ms=([0-9]+).*/\1 \2/' | sort -n) | awk '{printf "%.3f\n", $4/$2}' | median)"
    echo "exit codes: $(grep -oE '(record_exit|create_exit|exit)=[0-9]+' "$log" | sort | uniq -c | tr '\n' ' ')"
    ;;
esac
