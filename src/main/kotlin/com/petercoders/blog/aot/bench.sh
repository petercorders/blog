#!/usr/bin/env bash
# 사용: bench.sh <label> <n> <java 명령...>
# 같은 명령을 n회 돌려 벽시계 ms 와 스프링의 "Started ... in X seconds (process running for Y)" 줄을 기록하고 중앙값을 낸다.
# 절대값은 이 기기를 탄다. 다른 조건과의 비율만 본문에 쓴다. 실행 전 `uptime` 으로 load average 를 확인한다.
set -u
LABEL=$1; N=$2; shift 2
OUT=${RUNS:-$(cd "$(dirname "$0")" && pwd)/runs}; mkdir -p "$OUT"
LOG="$OUT/$LABEL.log"; : > "$LOG"
now_ms() { perl -MTime::HiRes=time -e 'printf "%d", time*1000'; }
median() { sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }
for i in $(seq 1 "$N"); do
  t0=$(now_ms)
  started=$("$@" 2>&1 | grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds \(process running for [0-9.]+\)')
  t1=$(now_ms)
  echo "$LABEL run=$i wall_ms=$((t1-t0)) $started" | tee -a "$LOG"
done
echo "$LABEL median_wall_ms=$(grep -oE 'wall_ms=[0-9]+' "$LOG" | cut -d= -f2 | median)" | tee -a "$LOG"
echo "$LABEL median_started_s=$(grep -oE 'in [0-9.]+ seconds' "$LOG" | awk '{print $2}' | median)" | tee -a "$LOG"
echo "$LABEL median_process_s=$(grep -oE 'running for [0-9.]+' "$LOG" | awk '{print $3}' | median)" | tee -a "$LOG"
