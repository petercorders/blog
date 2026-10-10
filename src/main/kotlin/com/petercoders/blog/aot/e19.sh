#!/usr/bin/env bash
# e19 — JDK 27 GA(Temurin 27+35)에서 이 글에 닿는 변화 셋.
#   a  fat jar + AOTCacheOutput: create 표(unregistered·aot-linked), 운영 class+load 의 jar:nested·shared 수,
#      "Not loaded from file: code source" 제외 줄 수(JDK-8380291)
#   b  F0/F1/E0/E1 라운드로빈(JDK 27) — fat jar 무캐시 대비 비율
#   c  AOTMode=required / on × 유효·깨진 캐시의 exit, 그리고 JDK 25·26 이 required 를 거부하는 메시지
#   d  training 출력의 ClassFile*(multi-release versions/24) 경고 — JDK-8380847 이 27 에서도 그대로인지
#   e  JDK 27 에서 AOTMode=on 이 'incompatible CompressedOops::base()' 로 가끔 실패하는 빈도(e16 에서 처음 관측).
#      같은 캐시로 on 을 N회, 그리고 -XX:+AOTCompatibleOopCompression 으로 만든 캐시로 N회. 운영 Heap base 도 같이 찍는다
# 사용: J27=<java> e19.sh a|b|c|d|e
source "$(dirname "$0")/r2-env.sh"
step=${1:?a|b|c|d|e}
J=${J27:?J27 환경변수 필요}
F=$R2/fat; A=$R2/dirA; B=$R2/dirB
ROUNDS=${ROUNDS:-15}

mk27() { # mk27 <dir> <cache> <target> <출력파일>
  (cd "$1"; [ -f "$2" ] || "$J" -XX:AOTCacheOutput="$2" -Xlog:aot=info -Dspring.context.exit=onRefresh -jar "$3" > "$4" 2>&1; echo "# cache $1/$2 size=$(stat -f %z "$2")")
}

case $step in
a)
  header e19-a "JDK 27 fat jar + AOT Cache: 저장 범위" "$J"
  mk27 "$F" fat27.aot $APPJAR train-fat27.out
  mk27 "$A" app27.aot application/$APPJAR train-app27.out
  for x in "$F/train-fat27.out" "$A/train-app27.out"; do
    echo "## $(basename "$x") — assembly 표"
    grep -E '^\[[0-9.]+s\]\[info *\]\[aot\] +(instance classes|boot|vm|platform|app|unregistered|\(hidden\)) +=' "$x" | sed -E 's/^\[[0-9.]+s\]\[info *\]\[aot\]//' | head -24
    echo "Not loaded from \"file:\" code source 줄 = $(grep -c 'Not loaded from "file:" code source' "$x")"
    grep 'Not loaded from "file:" code source' "$x" | head -2 | cut -c1-200 | sed 's/^/    /'
  done
  echo "## 운영 class+load(JDK 27)"
  (cd "$F"; "$J" -XX:AOTCache=fat27.aot -Xlog:class+load:file=cl27-F1.log:none -jar $APPJAR > /dev/null 2>&1; echo "F1 exit=$? $(count_sources cl27-F1.log) jar:nested=$(grep -c 'source: jar:nested:' cl27-F1.log)")
  (cd "$F"; "$J" -Xlog:class+load:file=cl27-F0.log:none -jar $APPJAR > /dev/null 2>&1; echo "F0 exit=$? $(count_sources cl27-F0.log) jar:nested=$(grep -c 'source: jar:nested:' cl27-F0.log)")
  (cd "$A"; "$J" -XX:AOTCache=app27.aot -Xlog:class+load:file=cl27-E1.log:none -jar application/$APPJAR > /dev/null 2>&1; echo "E1 exit=$? $(count_sources cl27-E1.log)")
  echo "## 대조: JDK 25 fat 캐시의 같은 표(r2 사전 절차 training 출력에는 -Xlog:aot=info 가 없어 다시 만든다)"
  (cd "$F"; rm -f fat25-info.aot; "$J25" -XX:AOTCacheOutput=fat25-info.aot -Xlog:aot=info -Dspring.context.exit=onRefresh -jar $APPJAR > train-fat25-info.out 2>&1
   grep -E '^\[[0-9.]+s\]\[info *\]\[aot\] +(instance classes|boot|vm|platform|app|unregistered|\(hidden\)) +=' train-fat25-info.out | sed -E 's/^\[[0-9.]+s\]\[info *\]\[aot\]//' | head -24)
  ;;
b)
  header e19-b "JDK 27 F0/F1/E0/E1 라운드로빈 ${ROUNDS}회" "$J"
  (cd "$A" && gate E1-27 "$J" app27.aot -jar application/$APPJAR)
  (cd "$F" && gate F1-27 "$J" fat27.aot -jar $APPJAR)
  conds=(F0 F1 E0 E1)
  for r in $(seq 1 "$ROUNDS"); do
    echo "## round $r uptime: $(uptime | sed 's/.*load averages*: //')"
    off=$(( (r-1) % 4 ))
    for k in 0 1 2 3; do
      c=${conds[$(( (k+off) % 4 ))]}
      case $c in
        F0) (cd "$F" && run_once "F0 r=$r" "$J" -jar $APPJAR);;
        F1) (cd "$F" && run_once "F1 r=$r" "$J" -XX:AOTCache=fat27.aot -jar $APPJAR);;
        E0) (cd "$A" && run_once "E0 r=$r" "$J" -jar application/$APPJAR);;
        E1) (cd "$A" && run_once "E1 r=$r" "$J" -XX:AOTCache=app27.aot -jar application/$APPJAR);;
      esac
    done
  done
  ;;
c)
  header e19-c "AOTMode=required / on × 유효·깨진 캐시" "$J" "$J25" "$J26"
  echo "# dirA = 유효(app27.aot), dirB = 앱 jar touch 로 깨진 배치. dirB 에 JDK 27 캐시를 만든 뒤 다시 touch 한다"
  (cd "$B"; if [ ! -f app27.aot ]; then touch -t 202609082350.45 application/$APPJAR; "$J" -XX:AOTCacheOutput=app27.aot -Dspring.context.exit=onRefresh -jar application/$APPJAR > /dev/null 2>&1; fi; touch application/$APPJAR; echo "# dirB 앱 jar mtime $(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' application/$APPJAR)")
  for mode in auto on required; do
    for d in dirA dirB; do
      (cd "$R2/$d"; "$J" -XX:AOTCache=app27.aot -XX:AOTMode=$mode -jar application/$APPJAR > out.txt 2> err.txt; rc=$?
       echo "JDK27 $mode $d exit=$rc $(grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' out.txt)"
       grep -hE '\]\[aot|Error occurred|Unable to use|^Error' out.txt err.txt | sed -E 's/^\[[0-9.]+s\]/    /; s/^([^ ])/    \1/' | head -5)
    done
  done
  echo "## JDK 25·26 에 required 를 주면"
  (cd "$A"; "$J25" -XX:AOTCache=app25.aot -XX:AOTMode=required -jar application/$APPJAR > out.txt 2>&1; echo "JDK25 required exit=$?"; head -3 out.txt | sed 's/^/    /')
  (cd "$A"; "$J26" -XX:AOTCache=app26.aot -XX:AOTMode=required -jar application/$APPJAR > out.txt 2>&1; echo "JDK26 required exit=$?"; head -3 out.txt | sed 's/^/    /')
  ;;
e)
  N=${N:-20}
  header e19-e "JDK 27 AOTMode=on 의 CompressedOops::base 불일치 빈도(${N}회씩)" "$J"
  (cd "$A"; rm -f app27-compat.aot; "$J" -XX:+UnlockDiagnosticVMOptions -XX:+AOTCompatibleOopCompression -XX:AOTCacheOutput=app27-compat.aot -Dspring.context.exit=onRefresh -jar application/$APPJAR > train-compat27.out 2>&1; echo "# app27-compat.aot exit=$? size=$(stat -f %z app27-compat.aot)")
  for c in app27.aot app27-compat.aot; do
    fail=0; ok=0; bases=""
    for i in $(seq 1 "$N"); do
      out=$(cd "$A"; "$J" -XX:AOTCache=$c -XX:AOTMode=on -Xlog:gc+init=info -jar application/$APPJAR 2>&1); rc=$?
      b=$(printf '%s\n' "$out" | grep -oE 'Heap address: 0x[0-9a-f]+' | head -1 | awk '{print $3}')
      m=$(printf '%s\n' "$out" | grep -oE 'incompatible CompressedOops::base\(\): 0x[0-9a-f]+ vs current 0x[0-9a-f]+' | head -1)
      if [ $rc -eq 0 ]; then ok=$((ok+1)); else fail=$((fail+1)); echo "  [$c] try=$i exit=$rc ${m:-$(printf '%s\n' "$out" | grep -E 'error' | head -1)}"; fi
      bases="$bases ${b:-?}"
    done
    echo "[$c] AOTMode=on ok=$ok fail=$fail / $N"
    echo "    운영 Heap address(성공한 실행의 gc+init): $(echo $bases | tr ' ' '\n' | sort | uniq -c | sort -rn | head -4 | tr '\n' ' ')"
  done
  echo "## 같은 조건 auto(기본값)에서는 어떻게 되나(실패 메시지가 찍힐 때도 캐시를 쓰는가)"
  for i in 1 2 3 4 5 6; do (cd "$A"; "$J" -XX:AOTCache=app27.aot -Xlog:class+load:file=cl27-e.log:none -jar application/$APPJAR > out.txt 2>&1; echo "  auto try=$i exit=$? $(count_sources cl27-e.log) $(grep -oE 'incompatible CompressedOops::base\(\)[^,]*' out.txt | head -1)"); done
  ;;
d)
  header e19-d "JDK 27 training 의 multi-release(ClassFile*) 경고" "$J"
  x=$A/train-app27.out
  echo "cannot be archived(warning) 줄 = $(grep -c 'cannot be archived' "$x")"
  grep 'cannot be archived' "$x" | sed -E 's/.*class ([^ ]+) cannot be archived.*/    \1/'
  echo "Skipping 줄(info 포함) 사유별:"
  grep 'Skipping' "$x" | sed -E 's/^\[[0-9.]+s\]\[([a-z]+) *\]\[aot\] Skipping [^:]+: /\1 | /' | sort | uniq -c | sort -rn | head -10 | sed 's/^/    /'
  echo "Proxy Skipping = $(grep 'Skipping' "$x" | grep -c Proxy)"
  echo "운영 class+load 에서 __dynamic_proxy__ = $(grep -c 'source: __dynamic_proxy__' "$A/cl27-E1.log"), ClassFile* 줄 출처:"
  grep -E 'classreading\.ClassFile(MetadataReaderFactory|MetadataReader) ' "$A/cl27-E1.log" | sed 's/^/    /' | cut -c1-200
  ;;
esac
