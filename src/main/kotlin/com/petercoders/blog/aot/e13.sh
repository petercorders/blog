#!/usr/bin/env bash
# e13 — ①·③·운영 절의 핵심 수치를 같은 라운드에서 다시 잰다(JDK 25.0.4.1, r2 사전 절차 통과 후).
#   a  F0/F1/E0/E1/E2 다섯 조건 15라운드 라운드로빈(라운드마다 순서를 한 칸씩 돌린다, 라운드마다 uptime)
#   b  조건마다 -Xlog:class+load 1회 → shared objects file 줄 수 / 총 줄 수
#   c  e10b 크기 조건 재실행 3회(1차 시도의 'size AOTMode=auto exit=1' 해명: 1바이트 덧붙이기 vs 클래스 추가)
#   d  빌드 게이트 명령(AOTMode=on + onRefresh)을 유효·깨진 캐시에 돌린다
#   e  검사를 지나치는 변경: lib jar 에서 클래스를 지우고 크기·mtime 을 되돌린 뒤 캐시가 그 클래스를 올리는지
# 사용: e13.sh a|b|c|d|e  (로그는 runs/e13-<x>-*.log)
source "$(dirname "$0")/r2-env.sh"
step=${1:?a|b|c|d|e}
ROUNDS=${ROUNDS:-15}
F=$R2/fat; A=$R2/dirA; B=$R2/dirB; C=$R2/dirC

# 조건 정의: 이름 → (작업 폴더, 옵션)
cond_dir() { case $1 in F0|F1) echo "$F";; E0|E1) echo "$A";; E2) echo "$B";; esac; }
cond_cmd() {
  case $1 in
    F0) echo "-jar $APPJAR";;
    F1) echo "-XX:AOTCache=fat25.aot -jar $APPJAR";;
    E0) echo "-jar application/$APPJAR";;
    E1) echo "-XX:AOTCache=app25.aot -jar application/$APPJAR";;
    E2) echo "-XX:AOTCache=app25.aot -jar application/$APPJAR";;
  esac
}

prep_broken_B() {
  # dirB 의 앱 jar 만 touch 해서 캐시를 깨뜨린다(내용 그대로, mtime 만 변경)
  if [ "$(stat -f '%Sm' -t '%Y%m%d%H%M.%S' "$B/application/$APPJAR")" = "202609082350.45" ]; then
    touch "$B/application/$APPJAR"
  fi
  echo "# dirB 앱 jar mtime: $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$B/application/$APPJAR") (dirA: $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$A/application/$APPJAR"))"
}

step_a() {
  header e13-a "F0/F1/E0/E1/E2 같은 라운드 라운드로빈 ${ROUNDS}회" "$J25"
  prep_broken_B
  echo "# 사전 게이트"
  (cd "$A" && gate E1 "$J25" app25.aot -jar application/$APPJAR)
  (cd "$F" && gate F1 "$J25" fat25.aot -jar $APPJAR)
  (cd "$B" && gate E2-expect-fail "$J25" app25.aot -jar application/$APPJAR) || true
  echo "# command: (cd <dir> && java <opts>) — F0: fat/ -jar · F1: fat/ -XX:AOTCache=fat25.aot -jar · E0: dirA -jar application/… · E1: dirA -XX:AOTCache=app25.aot · E2: dirB(앱 jar touch) -XX:AOTCache=app25.aot (auto)"
  local conds=(F0 F1 E0 E1 E2)
  for r in $(seq 1 "$ROUNDS"); do
    echo "## round $r uptime: $(uptime | sed 's/.*load averages*: //')"
    local off=$(( (r-1) % 5 ))
    for k in 0 1 2 3 4; do
      local c=${conds[$(( (k+off) % 5 ))]}
      (cd "$(cond_dir $c)" && run_once "$c r=$r" "$J25" $(cond_cmd $c))
    done
  done
}

summarize_a() {
  local log=$1
  echo "## 요약(같은 로그 안의 ${ROUNDS}라운드). ratio = 같은 라운드 F0 대비 비율의 중앙값(부하 변동을 라운드 안에서 상쇄)"
  python3 - "$log" <<'PY'
import re, sys, statistics as st
rows = {}
for line in open(sys.argv[1]):
    m = re.match(r'^(F0|F1|E0|E1|E2) r=(\d+) wall_ms=(\d+) exit=(\d+) Started \w+ in ([0-9.]+) seconds \(process running for ([0-9.]+)\)', line)
    if m:
        c, r = m.group(1), int(m.group(2))
        rows.setdefault(c, {})[r] = (int(m.group(3)), float(m.group(5)), float(m.group(6)))
f0 = rows.get('F0', {})
for c in ['F0', 'F1', 'E0', 'E1', 'E2']:
    d = rows.get(c, {})
    w = [v[0] for v in d.values()]; s = [v[1] for v in d.values()]; p = [v[2] for v in d.values()]
    rs = [d[r][1] / f0[r][1] for r in d if r in f0]
    rw = [d[r][0] / f0[r][0] for r in d if r in f0]
    print(f"{c} n={len(d)} median_wall_ms={st.median(w):.0f} median_started_s={st.median(s):.3f} median_process_s={st.median(p):.3f} "
          f"min_wall_ms={min(w)} min_started_s={min(s):.3f} started/F0(round-ratio median)={st.median(rs):.3f} wall/F0(round-ratio median)={st.median(rw):.3f}")
def wins(a, b):
    da, db = rows[a], rows[b]
    common = [r for r in da if r in db]
    return sum(1 for r in common if da[r][1] < db[r][1]), len(common)
for a, b in [('E2', 'F0'), ('E0', 'F0'), ('E2', 'E0'), ('F1', 'F0'), ('E1', 'F1'), ('E1', 'E0')]:
    k, n = wins(a, b)
    print(f"sign: {a} < {b} (Started) in {k}/{n} rounds")
PY
}

step_b() {
  header e13-b "조건마다 -Xlog:class+load 1회 — shared objects file 비율" "$J25"
  prep_broken_B
  echo "# command: (cd <dir> && java -Xlog:class+load:file=<f>:none <opts>) 후 grep -c 'source: shared objects file' / grep -c 'source:'"
  for c in F0 F1 E0 E1 E2; do
    local f=$RUNS/work-r2/classload-e13-$c.log
    (cd "$(cond_dir $c)" && "$J25" -Xlog:class+load:file="$f":none $(cond_cmd $c) > /dev/null 2>&1; echo "$c exit=$?")
    local total shared nested
    total=$(grep -c 'source:' "$f"); shared=$(grep -c 'source: shared objects file' "$f"); nested=$(grep -c 'source: jar:nested:' "$f")
    echo "$c class+load total=$total shared=$shared ($(awk -v a=$shared -v b=$total 'BEGIN{printf "%.1f", 100*a/b}')%) nonshared=$((total-shared)) jar:nested=$nested"
  done
  echo "# 운영 절 bash #3 형태(위와 같은 집계를 한 줄 명령으로):"
  echo '#   java -Xlog:class+load -XX:AOTCache=app.aot -jar application/app.jar | grep -c "source: shared objects file"'
  local one
  one=$(cd "$A" && "$J25" -Xlog:class+load -XX:AOTCache=app25.aot -jar application/$APPJAR | grep -c "source: shared objects file")
  echo "E1 one-liner shared=$one"
  one=$(cd "$B" && "$J25" -Xlog:class+load -XX:AOTCache=app25.aot -jar application/$APPJAR | grep -c "source: shared objects file")
  echo "E2 one-liner shared=$one"
}

step_c() {
  header e13-c "e10b 크기 조건 재실행 3회 — 1바이트 덧붙이기(1차 시도 추정) vs 클래스 추가 후 mtime 복원(재실험 방식)" "$J25"
  local D=$R2/dirD
  if [ ! -f "$D/app25.aot" ]; then
    mkdir -p "$D" && (cd "$R2" && "$J25" -Djarmode=tools -jar fat/$APPJAR extract --destination dirD/application > /dev/null 2>&1)
    (cd "$D" && "$J25" -XX:AOTCacheOutput=app25.aot -Dspring.context.exit=onRefresh -jar application/$APPJAR > train.log 2>&1)
  fi
  local J=$D/application/$APPJAR
  cp -p "$J" "$D/orig.jar.keep"
  (cd "$D" && gate dirD "$J25" app25.aot -jar application/$APPJAR)
  for i in 1 2 3; do
    echo "## try $i — (c1) 1바이트 덧붙이고 mtime 복원"
    cp -p "$D/orig.jar.keep" "$J"; printf 'x' >> "$J"; touch -r "$D/orig.jar.keep" "$J"
    echo "size: $(stat -f %z "$D/orig.jar.keep") -> $(stat -f %z "$J") mtime: $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$J")"
    (cd "$D" && "$J25" -XX:AOTCache=app25.aot -jar application/$APPJAR > out.txt 2> err.txt; rc=$?
      echo "c1 auto exit=$rc stdout_aot=$(grep -c '\]\[aot' out.txt) stderr_lines=$(wc -l < err.txt | tr -d ' ')"
      grep -E '\]\[aot' out.txt | head -2; head -3 err.txt; grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' out.txt)
    echo "## try $i — (c2) Marker 클래스 추가(jar uf) 후 mtime 복원"
    cp -p "$D/orig.jar.keep" "$J"
    (cd "$AOT" && mkdir -p "$D/mk" && "${J25%/java}/javac" -d "$D/mk" Marker.java && cd "$D/mk" && "${J25%/java}/jar" uf "$J" com/petercoders/blog/aot/Marker.class)
    touch -r "$D/orig.jar.keep" "$J"
    echo "size: $(stat -f %z "$D/orig.jar.keep") -> $(stat -f %z "$J") mtime: $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$J")"
    (cd "$D" && "$J25" -XX:AOTCache=app25.aot -jar application/$APPJAR > out.txt 2> err.txt; rc=$?
      echo "c2 auto exit=$rc stdout_aot=$(grep -c '\]\[aot' out.txt) stderr_lines=$(wc -l < err.txt | tr -d ' ')"
      grep -E '\]\[aot' out.txt | head -2; head -3 err.txt; grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' out.txt)
    (cd "$D" && "$J25" -XX:AOTCache=app25.aot -XX:AOTMode=on -jar application/$APPJAR > out.txt 2> err.txt; echo "c2 on exit=$? stdout_aot=$(grep -c '\]\[aot' out.txt) stderr_lines=$(wc -l < err.txt | tr -d ' ')")
  done
  echo "## 1바이트 덧붙인 jar 를 캐시 없이 띄우면(캐시와 무관하게 jar 자체가 깨졌는지)"
  cp -p "$D/orig.jar.keep" "$J"; printf 'x' >> "$J"
  (cd "$D" && "$J25" -jar application/$APPJAR > out.txt 2> err.txt; echo "c1 nocache exit=$? stderr_lines=$(wc -l < err.txt | tr -d ' ')"; head -3 err.txt; head -3 out.txt | cut -c1-200)
  cp -p "$D/orig.jar.keep" "$J"
  (cd "$D" && gate dirD-restored "$J25" app25.aot -jar application/$APPJAR)
}

step_d() {
  header e13-d "빌드 게이트 명령을 유효·깨진 캐시에 돌린다" "$J25"
  prep_broken_B
  echo "# command: java -XX:AOTCache=app25.aot -XX:AOTMode=on -Dspring.context.exit=onRefresh -jar application/$APPJAR"
  for d in dirA dirB; do
    echo "## $d"
    (cd "$R2/$d" && "$J25" -XX:AOTCache=app25.aot -XX:AOTMode=on -Dspring.context.exit=onRefresh -jar application/$APPJAR > out.txt 2> err.txt; rc=$?
      echo "exit=$rc stdout_lines=$(wc -l < out.txt | tr -d ' ') stderr_lines=$(wc -l < err.txt | tr -d ' ')"
      echo "--- stdout 의 aot 줄 전문 ---"; grep -E '\]\[aot' out.txt
      echo "--- stderr 전문 ---"; cat err.txt
      echo "--- stdout 전문(앞 12줄) ---"; head -12 out.txt | cut -c1-240)
  done
}

step_e() {
  header e13-e "검사를 지나치는 변경 — lib jar 에서 클래스를 지우고 크기·mtime 을 원래대로 맞춘다" "$J25"
  local LIB=spring-core-7.0.9.jar CLS=org/springframework/util/StringUtils.class
  local L=$C/application/lib/$LIB REF=$A/application/lib/$LIB
  (cd "$C" && gate dirC-before "$J25" app25.aot -jar application/$APPJAR)
  local orig; orig=$(stat -f %z "$REF")
  cp -p "$REF" "$L"
  zip -q -d "$L" "$CLS"
  local now; now=$(stat -f %z "$L")
  local diff=$((orig-now))
  echo "# $LIB: $CLS 삭제 후 크기 $orig -> $now (차이 $diff B)"
  # zip comment 로 원래 크기에 맞춘다. 주석 길이 = 차이(END 레코드의 주석 길이 필드는 이미 있으므로 정확히 그만큼 는다)
  python3 - "$L" "$diff" <<'PY'
import sys
p, n = sys.argv[1], int(sys.argv[2])
b = bytearray(open(p, 'rb').read())
i = b.rfind(b'PK\x05\x06')
assert i >= 0 and len(b) - (i + 22) == 0, "기존 zip comment 가 있다"
b[i+20:i+22] = n.to_bytes(2, 'little')
b += b'#' * n
open(p, 'wb').write(b)
PY
  touch -r "$REF" "$L"
  echo "# 맞춘 뒤: size=$(stat -f %z "$L") (원본 $orig) mtime=$(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$L") (원본 $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$REF")) sha256 원본=$(shasum -a 256 "$REF" | cut -c1-16)… 변경=$(shasum -a 256 "$L" | cut -c1-16)…"
  echo "# jar 안에 $CLS 가 있는가: $(unzip -l "$L" | grep -c "$CLS") (원본: $(unzip -l "$REF" | grep -c "$CLS"))"
  echo "## (1) 캐시 있음(auto)"
  (cd "$C" && "$J25" -Xlog:class+load:file=cl-e.log:none -XX:AOTCache=app25.aot -jar application/$APPJAR > out.txt 2> err.txt; rc=$?
    echo "exit=$rc stdout_aot=$(grep -c '\]\[aot' out.txt) stderr_lines=$(wc -l < err.txt | tr -d ' ')"; grep -E '\]\[aot' out.txt | head -3
    grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds \(process running for [0-9.]+\)' out.txt
    echo "StringUtils class+load: $(grep -E 'org.springframework.util.StringUtils ' cl-e.log)"
    echo "$(count_sources cl-e.log)")
  echo "## (2) 같은 캐시로 AOTMode=on"
  (cd "$C" && "$J25" -XX:AOTCache=app25.aot -XX:AOTMode=on -jar application/$APPJAR > out.txt 2> err.txt; echo "on exit=$? stdout_aot=$(grep -c '\]\[aot' out.txt)"; grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' out.txt)
  echo "## (3) 대조: 같은 폴더를 캐시 없이"
  (cd "$C" && "$J25" -jar application/$APPJAR > out.txt 2> err.txt; rc=$?
    echo "nocache exit=$rc stderr_lines=$(wc -l < err.txt | tr -d ' ') stdout_lines=$(wc -l < out.txt | tr -d ' ')"
    grep -hE 'NoClassDefFoundError|ClassNotFoundException|Exception in thread' out.txt err.txt | head -3 | cut -c1-240)
  echo "## 복원"
  cp -p "$REF" "$L"
  (cd "$C" && gate dirC-restored "$J25" app25.aot -jar application/$APPJAR)
}

case $step in
  a) log=$RUNS/e13-a-roundrobin.log; step_a > "$log" 2>&1; summarize_a "$log" >> "$log"; tail -6 "$log";;
  b) step_b > "$RUNS/e13-b-classload.log" 2>&1; cat "$RUNS/e13-b-classload.log" | grep -v '^#   ';;
  c) step_c > "$RUNS/e13-c-size.log" 2>&1; cat "$RUNS/e13-c-size.log" | grep -v '^#   ';;
  d) step_d > "$RUNS/e13-d-gate.log" 2>&1; cat "$RUNS/e13-d-gate.log" | grep -v '^#   ';;
  e) step_e > "$RUNS/e13-e-samesize-lib.log" 2>&1; cat "$RUNS/e13-e-samesize-lib.log" | grep -v '^#   ';;
esac
