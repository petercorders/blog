#!/usr/bin/env bash
# e21 — dynamic Proxy 클래스가 캐시에 실리는가(JDK 26.0.2.1, 대조로 JDK 25.0.4.1).
#   1) 새로 만든 캐시(app26.aot / app25.aot)로 기동하며 -Xlog:class+load → source: __dynamic_proxy__ 줄 수,
#      jdk.proxy* 클래스 중 shared objects file 에서 온 줄 수
#   2) 같은 training 을 -Xlog:aot=info 로 다시 돌려 Proxy 관련 Skipping 줄 수(JDK 26 은 info 로만 찍힌다)
source "$(dirname "$0")/r2-env.sh"
D=$R2/dirA
header e21 "dynamic Proxy 캐시 여부(JDK 26, 대조 JDK 25)" "$J26" "$J25"
for v in 26 25; do
  J=$J25; [ $v = 26 ] && J=$J26
  echo "## JDK $v — 운영 기동(캐시 app$v.aot)"
  (cd "$D" && "$J" -XX:AOTCache=app$v.aot -Xlog:class+load:file=cl-e21-$v.log:none -jar application/$APPJAR > /dev/null 2>&1; echo "exit=$?")
  echo "class+load total=$(grep -c 'source:' "$D/cl-e21-$v.log") shared=$(grep -c 'source: shared objects file' "$D/cl-e21-$v.log")"
  echo "source: __dynamic_proxy__ 줄 수 = $(grep -c 'source: __dynamic_proxy__' "$D/cl-e21-$v.log")"
  echo "jdk.proxy* 클래스 줄 수 = $(grep -cE '^(jdk\.proxy[0-9]+\.)?\$?Proxy[0-9]+ |^jdk\.proxy[0-9]+\.' "$D/cl-e21-$v.log") 중 shared = $(grep -E '^jdk\.proxy[0-9]+\.' "$D/cl-e21-$v.log" | grep -c 'shared objects file')"
  grep -E '^jdk\.proxy[0-9]+\.' "$D/cl-e21-$v.log" | head -3 | sed 's/^/    /'
  echo "## JDK $v — training 을 -Xlog:aot=info(stdout) 로 다시(같은 명령, 캐시 이름만 다르게. 자식 assembly 도 같은 stdout 에 찍힌다)"
  (cd "$D" && "$J" -XX:AOTCacheOutput=e21-$v.aot -Xlog:aot=info -Dspring.context.exit=onRefresh -jar application/$APPJAR > train-e21-$v.out 2>&1; echo "exit=$?")
  local_out=$D/train-e21-$v.out
  echo "Skipping 줄: warning=$(grep -c 'warning.*Skipping' "$local_out") info=$(grep -c '\[info *\]\[aot\] Skipping' "$local_out")"
  grep 'Skipping' "$local_out" | sed -E 's/^\[[0-9.]+s\]\[([a-z]+) *\]\[aot\] Skipping [^:]+: /\1 | /' | sort | uniq -c | sort -rn | head -10 | sed 's/^/    레벨|사유: /'
  echo "    Proxy 관련 Skipping: $(grep 'Skipping' "$local_out" | grep -c 'Proxy')"
  grep 'Skipping' "$local_out" | grep 'Proxy' | head -2 | cut -c1-200 | sed 's/^/    /'
done
