#!/usr/bin/env bash
# r2 사전 절차(실험 번호 없음).
#   pre-oldcache : 옛 runs/work 의 앱 jar mtime 이 바뀌어 옛 캐시가 무효였다는 것을 재현하고, mtime 을 되돌려 살린다.
#   pre-caches   : 고정 jar 를 runs/work-r2/ 에 펼치고 JDK 25·26 캐시(펼친 배치·fat jar)를 공식 Dockerfile 방식
#                  (-XX:AOTCacheOutput + -Dspring.context.exit=onRefresh)으로 새로 만든 뒤 AOTMode=on 게이트를 건다.
source "$(dirname "$0")/r2-env.sh"
step=${1:-all}

pre_oldcache() {
  local OLD=$RUNS/work/application/$APPJAR
  header r2-pre-oldcache "옛 runs/work 캐시가 무효였던 이유(앱 jar mtime)와 mtime 복원 후 회복" "$J25"
  echo "# command: (cd runs/work && java -XX:AOTCache=blog.aot -XX:AOTMode=on -jar application/$APPJAR)"
  echo "## 현재(복원 후): $(stat -f '%Sm %z' -t '%Y-%m-%d %H:%M:%S' "$OLD")"
  (cd "$RUNS/work" && "$J25" -XX:AOTCache=blog.aot -XX:AOTMode=on -jar application/$APPJAR 2>&1 | grep -E 'aot\]|Started'; echo "on exit=${PIPESTATUS[0]}")
  echo "## nowcheck 당시 상태 재현: touch -t 202609232001.05 (nowcheck 로그 시각의 mtime)"
  touch -t 202609232001.05 "$OLD"
  echo "mtime: $(stat -f '%Sm %z' -t '%Y-%m-%d %H:%M:%S' "$OLD")"
  (cd "$RUNS/work" && "$J25" -XX:AOTCache=blog.aot -XX:AOTMode=on -jar application/$APPJAR 2>&1 | grep -E 'aot\]|Started'; echo "on exit=${PIPESTATUS[0]}")
  (cd "$RUNS/work" && "$J25" -XX:AOTCache=blog.aot -jar application/$APPJAR 2>&1 | grep -E 'aot\]|Started'; echo "auto exit=${PIPESTATUS[0]}")
  echo "## 되돌림: touch -t 202609082350.45 (캐시가 기록한 값)"
  touch -t 202609082350.45 "$OLD"
  echo "mtime: $(stat -f '%Sm %z' -t '%Y-%m-%d %H:%M:%S' "$OLD")"
  (cd "$RUNS/work" && "$J25" -XX:AOTCache=blog.aot -XX:AOTMode=on -jar application/$APPJAR 2>&1 | grep -E 'aot\]|Started'; echo "on exit=${PIPESTATUS[0]}")
  echo "## nowcheck 로그 3개의 생성·수정 시각(stat birth / mtime)"
  for f in nocache-nowcheck.log broken-cache-nowcheck.log valid-cache-nowcheck.log; do
    stat -f "  %N birth=%SB mtime=%Sm" -t '%Y-%m-%d %H:%M:%S' "$RUNS/$f" | sed "s#$RUNS/##"
  done
  echo "## 해석: broken 이 20:01:05 에 앱 jar 를 touch 했고(jar mtime 20:01:05 와 broken 로그 birth 가 같다),"
  echo "##       valid 는 그 뒤 20:01:16 에 돌았으며 mtime 은 2026-10-08 까지 되돌려지지 않았다."
  echo "##       따라서 valid-cache-nowcheck(0.352s)는 무효 캐시(fallback) 상태의 측정이다. 세 조건 모두 사실상 캐시 없이 돈 값이다."
}

# make_cache <label> <java> <작업폴더> <cache 이름> <-jar 대상(작업폴더 기준)>
make_cache() {
  local label=$1 java=$2 dir=$3 cache=$4 target=$5
  local t0 t1 rc
  ( cd "$dir"
    t0=$(now_ms)
    "$java" -XX:AOTCacheOutput="$cache" -Dspring.context.exit=onRefresh -jar "$target" > "train-$label.log" 2>&1; rc=$?
    t1=$(now_ms)
    echo "[$label] one-line training+assembly exit=$rc wall_ms=$((t1-t0)) size=$(stat -f %z "$cache" 2>/dev/null)"
    grep -nE 'Launching child|Picked up JAVA_TOOL_OPTIONS|AOTCache creation is complete|Exception|Error' "train-$label.log" | cut -c1-220 | sed 's/^/    /'
  )
}

pre_caches() {
  header r2-pre-caches "work-r2 에 고정 jar 를 펼치고 JDK 25·26 캐시를 한 줄 방식(onRefresh)으로 새로 만든다" "$J25" "$J26"
  mkdir -p "$R2"
  mkdir -p "$R2/fat"
  cp -p "$PIN" "$R2/fat/$APPJAR"
  echo "# fat/$APPJAR = 고정 jar 사본(cp -p): $(stat -f '%Sm %z' -t '%Y-%m-%d %H:%M:%S' "$R2/fat/$APPJAR")"
  for d in dirA dirB dirC; do
    if [ ! -d "$R2/$d/application" ]; then
      (cd "$R2" && mkdir -p $d && "$J25" -Djarmode=tools -jar fat/$APPJAR extract --destination $d/application > /dev/null 2>&1)
    fi
    echo "# $d/application/$APPJAR: $(stat -f '%Sm %z' -t '%Y-%m-%d %H:%M:%S' "$R2/$d/application/$APPJAR"), lib mtime 예: $(stat -f '%Sm' -t '%Y-%m-%d' "$R2/$d/application/lib/spring-core-7.0.9.jar")"
  done
  echo "# command: java -XX:AOTCacheOutput=<cache> -Dspring.context.exit=onRefresh -jar <target>"
  make_cache jdk25-ext "$J25" "$R2/dirA" app25.aot application/$APPJAR
  make_cache jdk25-ext "$J25" "$R2/dirB" app25.aot application/$APPJAR
  make_cache jdk25-ext "$J25" "$R2/dirC" app25.aot application/$APPJAR
  make_cache jdk26-ext "$J26" "$R2/dirA" app26.aot application/$APPJAR
  make_cache jdk25-fat "$J25" "$R2/fat"  fat25.aot $APPJAR
  make_cache jdk26-fat "$J26" "$R2/fat"  fat26.aot $APPJAR
  echo "## 게이트(AOTMode=on, 운영과 같은 옵션 = -jar 만)"
  for d in dirA dirB dirC; do (cd "$R2/$d" && gate "$d-25" "$J25" app25.aot -jar application/$APPJAR); done
  (cd "$R2/dirA" && gate dirA-26 "$J26" app26.aot -jar application/$APPJAR)
  (cd "$R2/fat" && gate fat-25 "$J25" fat25.aot -jar $APPJAR)
  (cd "$R2/fat" && gate fat-26 "$J26" fat26.aot -jar $APPJAR)
  ls -l "$R2"/*/*.aot
}

case $step in
  oldcache) pre_oldcache ;;
  caches) pre_caches ;;
  all) pre_oldcache; pre_caches ;;
esac
