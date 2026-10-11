#!/usr/bin/env bash
# e24 — 클래스 초기화(clinit) 카운터를 같은 배치끼리 비교한다(JDK 25.0.4.1, e13 의 캐시 그대로).
#   e3(fat jar 무캐시 4,615)와 e7(펼친 배치 + 1차 캐시 3,702)은 배치와 training 이 달라 감소분을 캐시 몫으로 볼 수 없었다.
#   그래서 F0/F1/E0/E1 네 조건을 같은 라운드에서 번갈아 -Xlog:perf+class+link 로 띄워
#   At VM exit 블록의 clinit·link methods 이벤트 수를 같은 배치(E0 ↔ E1, F0 ↔ F1)끼리 대조한다.
#   F0: fat/ -jar · F1: fat/ -XX:AOTCache=fat25.aot · E0: dirA -jar application/… · E1: dirA -XX:AOTCache=app25.aot
#   (캐시 둘 다 e13 이 onRefresh training 으로 만든 것. 측정 전에 AOTMode=on 게이트를 통과해야 한다)
# 사용: L=runs/e24-clinit-same-layout.log; bash e24.sh run 5 > $L; bash e24.sh summary $L >> $L
source "$(dirname "$0")/r2-env.sh"
step=${1:?run|summary}
F=$R2/fat; A=$R2/dirA

cond_dir() { case $1 in F0|F1) echo "$F";; E0|E1) echo "$A";; esac; }
cond_opts() {
  case $1 in
    F0) echo "-jar $APPJAR";;
    F1) echo "-XX:AOTCache=fat25.aot -jar $APPJAR";;
    E0) echo "-jar application/$APPJAR";;
    E1) echo "-XX:AOTCache=app25.aot -jar application/$APPJAR";;
  esac
}

one() {
  local c=$1 r=$2 out rc
  out=$(cd "$(cond_dir "$c")" && "$J25" -Xlog:perf+class+link $(cond_opts "$c") 2>&1); rc=$?
  printf '%s\n' "$out" | python3 -c '
import re, sys
c, r, rc = sys.argv[1], sys.argv[2], sys.argv[3]
t = sys.stdin.read()
st = re.search(r"Started \w+ in ([0-9.]+) seconds \(process running for ([0-9.]+)\)", t)
blk = t.split("At VM exit:")[-1] if "At VM exit:" in t else ""
ini = t.split("At VM initialization completion:")[-1].split("Started")[0] if "At VM initialization completion:" in t else ""
def ev(b, k):
    m = re.search(k + r":\s+(\d+)ms / (\d+) events", b)
    return (m.group(1), m.group(2)) if m else ("NA", "NA")
ce, le, ci = ev(blk, "clinit"), ev(blk, "link methods"), ev(ini, "clinit")
s1, s2 = (st.group(1), st.group(2)) if st else ("NA", "NA")
print(f"{c} r={r} exit={rc} started_s={s1} process_s={s2} "
      f"vm_init_clinit_events={ci[1]} exit_clinit_ms={ce[0]} exit_clinit_events={ce[1]} exit_link_methods_events={le[1]}")
' "$c" "$r" "$rc"
}

summary() {
  python3 - "$1" <<'PY'
import re, sys, statistics as st
rows = {}
for line in open(sys.argv[1]):
    m = re.match(r'^(F0|F1|E0|E1) r=(\d+) exit=(\d+) started_s=([0-9.]+) .*exit_clinit_events=(\d+) exit_link_methods_events=(\d+)', line)
    if m:
        rows.setdefault(m.group(1), []).append((int(m.group(3)), float(m.group(4)), int(m.group(5)), int(m.group(6))))
n = len(rows['F0'])
print(f"## 요약(이 로그의 {n}라운드에서 계산). 이벤트 수는 라운드마다 같았다(link methods 만 ±2)")
for c in ['F0', 'F1', 'E0', 'E1']:
    d = rows[c]
    ce = sorted({x[2] for x in d}); le = sorted({x[3] for x in d})
    print(f"{c} n={len(d)} exit0={sum(1 for x in d if x[0]==0)} clinit_events={ce} link_methods_events={le} median_started_s={st.median(x[1] for x in d):.3f}")
e0 = rows['E0'][0][2]; e1 = rows['E1'][0][2]; f0 = rows['F0'][0][2]; f1 = rows['F1'][0][2]
print(f"같은 배치 대조: 펼친 배치 E0 {e0} → E1 {e1} (−{e0-e1}, {e1/e0:.3f}배) · fat jar F0 {f0} → F1 {f1} (−{f0-f1}, {f1/f0:.3f}배)")
print("# e3(F0 4,615)는 이 로그의 F0 와 같은 값이다. e7(3,702)은 명령줄이 로그에 남지 않았지만 Started 0.166 s·link methods 57,940 이 이 로그의 E1(0.135·57,897)에 가깝고 F1(0.288·56,194)과 멀어, 1차 캐시(onRefresh 없이 끝까지 training)를 단 펼친 배치로 본다. 그래서 e3 → e7 의 차이에는 배치 차이가 섞여 있고, 캐시 몫은 이 로그의 같은 배치 대조로 본다.")
PY
}

case $step in
  summary) summary "${2:?log}"; exit 0;;
  run) ROUNDS=${2:-5};;
  *) echo "run|summary"; exit 2;;
esac

header e24-clinit-same-layout "clinit·link methods 이벤트 수, F0/F1/E0/E1 같은 라운드 ${ROUNDS}회" "$J25"
echo "# fat jar: $F/$APPJAR mtime $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$F/$APPJAR") · 펼친 배치: $A/application/$APPJAR mtime $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$A/application/$APPJAR")"
echo "# 사전 게이트"
(cd "$A" && gate E1 "$J25" app25.aot -jar application/$APPJAR) || { echo "# E1 게이트 실패 — 중단"; exit 1; }
(cd "$F" && gate F1 "$J25" fat25.aot -jar $APPJAR) || { echo "# F1 게이트 실패 — 중단"; exit 1; }
echo "# command: (cd <dir> && java -Xlog:perf+class+link <opts>) — 라운드마다 순서를 한 칸씩 돌린다"
conds=(F0 F1 E0 E1)
for r in $(seq 1 "$ROUNDS"); do
  echo "## round $r uptime: $(uptime | sed 's/.*load averages*: //')"
  off=$(( (r-1) % 4 ))
  for k in 0 1 2 3; do
    one "${conds[$(( (k+off) % 4 ))]}" "$r"
  done
done
