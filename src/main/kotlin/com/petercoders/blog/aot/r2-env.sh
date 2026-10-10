#!/usr/bin/env bash
# r2(2026-10) 실험 공통 설정. 각 실험 스크립트가 `source r2-env.sh` 로 읽는다.
#
# 고정 기준
#   - 측정 jar 는 runs/pinned/blog-0.0.1-SNAPSHOT.jar 하나다(2026-09-08 23:50:45, 14,573,458 B,
#     sha256 89abfdeb…52b7b). 커밋 f3c9bfb 의 build.gradle.kts(spring-boot-starter + kotlin-reflect)로 만든 것이다.
#     이후 커밋(e56310e jdbc, 5c4fccb security·web)이 의존을 더해 지금 HEAD 로 bootJar 하면 다른 앱이 되므로
#     r2 실험은 ./gradlew bootJar 를 돌리지 않고 이 사본만 쓴다.
#   - 모든 캐시는 runs/work-r2/ 아래에 새로 만든다. 옛 runs/work 의 캐시는 쓰지 않는다.
#   - 측정 전에 AOTMode=on 게이트(exit 0)를 통과한 캐시만 쓴다.
#
# JDK
#   J25 = Microsoft 25.0.4.1+1-LTS (주 측정), J26 = OpenJDK 26.0.2.1+1-7 (대조)
#   J27 = Temurin 27+35, J21 = Temurin 21.0.12.1+1-LTS — 로컬에 설치하지 않고 tarball 을 풀어 절대경로로 쓴다.
#         (java_home -v 는 없는 버전을 상위로 폴백하므로 쓰지 않는다)
set -u
AOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RUNS=$AOT/runs
R2=$RUNS/work-r2
PIN=$RUNS/pinned/blog-0.0.1-SNAPSHOT.jar
PIN_SHA=89abfdeba6c568ea8fdd6d34a69a238d7a2a602185e1d3131f607d6e81152b7b
J25=${J25:-$HOME/Library/Java/JavaVirtualMachines/ms-25.0.4.1/Contents/Home/bin/java}
J26=${J26:-$HOME/Library/Java/JavaVirtualMachines/openjdk-26.0.2.1/Contents/Home/bin/java}
J27=${J27:-}
J21=${J21:-}
APPJAR=blog-0.0.1-SNAPSHOT.jar

now_ms() { perl -MTime::HiRes=time -e 'printf "%d", time*1000'; }
median() { sort -n | awk '{a[NR]=$1} END{ if (NR==0) print "NA"; else print a[int((NR+1)/2)] }'; }

# header <experiment-id> <설명> <java...>
header() {
  local id=$1 desc=$2; shift 2
  echo "# experiment: $id — $desc"
  echo "# date: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "# uptime: $(uptime)"
  for j in "$@"; do
    echo "# java -version ($j):"
    "$j" -version 2>&1 | sed 's/^/#   /'
  done
  echo "# pinned jar sha256: $(shasum -a 256 "$PIN" | cut -c1-64) (기대값 $PIN_SHA)"
}

# 한 번 띄우고 Started 줄의 두 숫자와 벽시계 ms 를 한 줄로 낸다. 종료 코드도 남긴다.
# run_once <label> <java...>
run_once() {
  local label=$1; shift
  local t0 t1 out rc
  t0=$(now_ms)
  out=$("$@" 2>&1); rc=$?
  t1=$(now_ms)
  local st
  st=$(printf '%s\n' "$out" | grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds \(process running for [0-9.]+\)' | head -1)
  echo "$label wall_ms=$((t1-t0)) exit=$rc $st"
}

# gate <label> <java> <캐시> <나머지 옵션...> — AOTMode=on 으로 한 번 띄워 exit 0 인지 본다
gate() {
  local label=$1 java=$2 cache=$3; shift 3
  local out rc
  out=$("$java" -XX:AOTCache="$cache" -XX:AOTMode=on "$@" 2>&1); rc=$?
  echo "# gate[$label]: AOTMode=on exit=$rc $(printf '%s\n' "$out" | grep -E '\[(warning|error) *\]\[aot\]' | head -3 | tr '\n' ' ')"
  return $rc
}

# 줄 단위 집계: class+load 로그에서 source 별 개수
count_sources() {
  local f=$1
  local total shared
  total=$(grep -c 'source:' "$f")
  shared=$(grep -c 'source: shared objects file' "$f")
  echo "class+load total=$total shared=$shared nonshared=$((total-shared))"
}
