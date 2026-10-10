#!/usr/bin/env bash
# e16-rr — e16 의 'Started 5회 중앙값'은 e17-F(Rosetta training)와 겹쳐 돌아 부하가 섞였다. 같은 캐시로 라운드로빈을 다시 돈다.
#   조건(JDK 25.0.4.1): N0 캐시·agent 없음 / K0 base 캐시·agent 없음 / Ndd 캐시 없음 + dd / Add base 캐시 + dd(= addmods 불일치로 버려짐)
#                       Bdd training 에도 dd 를 붙인 캐시 + dd / Cdd 세 단계 --add-modules 캐시 + dd
# 사용: e16-rr.sh [25|26]
source "$(dirname "$0")/r2-env.sh"
v=${1:-25}
J=$J25; [ "$v" = 26 ] && J=$J26
D=$R2/dirA; AG=$R2/agents
ROUNDS=${ROUNDS:-10}
DD=(-javaagent:$AG/dd-java-agent-1.67.0.jar -Ddd.trace.agent.port=1 -Ddd.instrumentation.telemetry.enabled=false -Ddd.remote_config.enabled=false)
AM=--add-modules=java.instrument
opts() {
  case $1 in
    N0) echo "";;
    K0) echo "-XX:AOTCache=e16-$v-base.aot";;
    Ndd) echo "${DD[*]}";;
    Add) echo "-XX:AOTCache=e16-$v-base.aot ${DD[*]}";;
    Bdd) echo "-XX:AOTCache=e16-$v-trdd.aot ${DD[*]}";;
    Cdd) echo "-XX:AOTCache=e16-$v-addmods.aot $AM ${DD[*]}";;
  esac
}
header "e16-rr-jdk$v" "agent 조합별 Started 라운드로빈 ${ROUNDS}회" "$J"
conds=(N0 K0 Ndd Add Bdd Cdd)
for r in $(seq 1 "$ROUNDS"); do
  echo "## round $r uptime: $(uptime | sed 's/.*load averages*: //')"
  off=$(( (r-1) % 6 ))
  for k in 0 1 2 3 4 5; do
    c=${conds[$(( (k+off) % 6 ))]}
    (cd "$D" && run_once "$c r=$r" "$J" $(opts $c) -jar application/$APPJAR)
  done
done
