#!/usr/bin/env bash
# e16 — agent 구성(training·assembly·운영)과 AOT Cache. JDK 25.0.4.1 / 26.0.2.1 / 27 GA.
#   agent 넷: (1) noop.jar  (2) transform.jar(BlogApplication 바이트를 바꾼다)  (3) dd-java-agent 1.67.0(백엔드 없이)
#             (4) libe16native-<v>.dylib(can_generate_all_class_hook_events 권한만 요청하는 native agent)
#   조합: A training 무agent → 운영 agent / B training agent → 운영 agent
#         C training·assembly·운영 모두 --add-modules=java.instrument, 운영에만 agent / D training 무agent → 운영 native agent
# 사용: e16.sh build | e16.sh 25|26|27   (J27 환경변수로 JDK 27 java 경로를 준다)
source "$(dirname "$0")/r2-env.sh"
AG=$R2/agents
D=$R2/dirA
APPO=(-jar application/$APPJAR)
TO=${TO:-90}
tmo() { perl -e 'alarm shift; exec @ARGV' "$TO" "$@"; }

build() {
  header e16-build "agent 빌드" "$J25"
  mkdir -p "$AG/cls"
  "${J25%/java}/javac" --release 25 -d "$AG/cls" "$AOT"/e16/NoopAgent.java "$AOT"/e16/TransformAgent.java && echo "javac exit=0"
  printf 'Premain-Class: e16.NoopAgent\n' > "$AG/noop.mf"
  printf 'Premain-Class: e16.TransformAgent\n' > "$AG/transform.mf"
  (cd "$AG/cls" && "${J25%/java}/jar" cfm ../noop.jar ../noop.mf e16/NoopAgent.class && "${J25%/java}/jar" cfm ../transform.jar ../transform.mf 'e16/TransformAgent.class' 'e16/TransformAgent$1.class')
  for v in 25 26 27; do
    local J; J=$(jfor $v) || continue
    local JH=${J%/bin/java}
    clang -shared -fPIC -I"$JH/include" -I"$JH/include/darwin" -o "$AG/libe16native-$v.dylib" "$AOT/e16/e16_native.c" && echo "native agent for JDK $v built (headers $JH/include)"
  done
  ls -l "$AG"
}

jfor() { case $1 in 25) echo "$J25";; 26) echo "$J26";; 27) [ -n "$J27" ] && echo "$J27" || return 1;; esac; }

# train <label> <cache> <옵션...> — 한 줄 방식(onRefresh). training 출력은 train-<cache>.out 에 남긴다.
train() {
  local label=$1 c=$2; shift 2
  (cd "$D"; rm -f "$c"
   tmo "$J" "$@" -XX:AOTCacheOutput="$c" -Xlog:aot=info -Dspring.context.exit=onRefresh "${APPO[@]}" > "train-$c.out" 2>&1; local rc=$?
   echo "[train $label] exit=$rc cache=$(stat -f %z "$c" 2>/dev/null || echo 없음) From_ClassFileLoadHook=$(grep -c 'From ClassFileLoadHook' "train-$c.out") $(grep -m1 -oE 'Picked up JAVA_TOOL_OPTIONS:.*' "train-$c.out" | sed -E 's#-Djava.class.path=[^ ]+ ##' | cut -c1-200)"
   grep 'From ClassFileLoadHook' "train-$c.out" | sed -E 's/^\[[0-9.]+s\]/    /' | head -4
   grep -E '\[e16\]' "train-$c.out" | sort | uniq -c | sed 's/^/    /' | head -4)
}

# probe <label> <cache> <운영 옵션...>
probe() {
  local label=$1 c=$2; shift 2
  (cd "$D"
   tmo "$J" "$@" -XX:AOTCache="$c" -Xlog:class+load:file=cl-e16.log:none "${APPO[@]}" > out.txt 2> err.txt; local rc=$?
   tmo "$J" "$@" -XX:AOTCache="$c" -XX:AOTMode=on "${APPO[@]}" > out-on.txt 2> err-on.txt; local rcon=$?
   echo "[$label] cache=$c auto_exit=$rc shared=$(grep -c 'source: shared objects file' cl-e16.log)/$(grep -c 'source:' cl-e16.log) on_exit=$rcon $(grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' out.txt | head -1)"
   grep -hE '\]\[aot' out.txt | sed -E 's/^\[[0-9.]+s\]/    auto:/' | head -3
   grep -hE '\]\[aot|Error occurred|Unable to use|^Error' out-on.txt err-on.txt | sed -E 's/^\[[0-9.]+s\]/    on  :/; s/^([^ ])/    on  : \1/' | head -3
   grep -hE '\[e16\]' out.txt err.txt | sort | uniq -c | sed 's/^/    agent: /' | head -3
   grep -hE 'ERROR|Exception' err.txt out.txt | grep -v 'datadog.trace.agent.common.writer' | head -2 | cut -c1-200 | sed 's/^/    err: /')
}

started5() { # started5 <label> <옵션...> — Started 5회 중앙값
  local label=$1; shift
  local vals=()
  for i in 1 2 3 4 5; do
    vals+=("$(cd "$D"; tmo "$J" "$@" "${APPO[@]}" 2>/dev/null | grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' | awk '{print $4}')")
  done
  echo "[$label] Started x5 = ${vals[*]} median=$(printf '%s\n' "${vals[@]}" | median)"
}

run_jdk() {
  local v=$1
  J=$(jfor $v) || { echo "J27 없음"; exit 1; }
  local NOOP=-javaagent:$AG/noop.jar XF=-javaagent:$AG/transform.jar DD=-javaagent:$AG/dd-java-agent-1.67.0.jar
  local DDOPT=(-Ddd.trace.agent.port=1 -Ddd.instrumentation.telemetry.enabled=false -Ddd.remote_config.enabled=false)
  local NAT=-agentpath:$AG/libe16native-$v.dylib
  local AM=--add-modules=java.instrument
  header "e16-jdk$v" "agent 구성 × AOT Cache" "$J"
  echo "# agents: noop.jar, transform.jar, dd-java-agent-1.67.0.jar(sha1 8da71687…, -Ddd.trace.agent.port=1 로 백엔드 없이), libe16native-$v.dylib"
  echo "## caches(training, 한 줄 방식 + onRefresh, -Xlog:aot=info 로 training 출력 보관)"
  train base     e16-$v-base.aot
  train tr-noop  e16-$v-trnoop.aot  $NOOP
  train tr-xform e16-$v-trxform.aot $XF
  train tr-dd    e16-$v-trdd.aot    $DD "${DDOPT[@]}"
  train addmods  e16-$v-addmods.aot $AM
  echo "## (C 보조) 3단계로 --add-modules 를 record·create 양쪽에 명시"
  (cd "$D"; rm -f e16-$v-am3.aot e16-$v-am3.aotconf
   tmo "$J" $AM -XX:AOTMode=record -XX:AOTConfiguration=e16-$v-am3.aotconf -Dspring.context.exit=onRefresh "${APPO[@]}" > /dev/null 2>&1; echo "record exit=$?"
   tmo "$J" $AM -XX:AOTMode=create -XX:AOTConfiguration=e16-$v-am3.aotconf -XX:AOTCache=e16-$v-am3.aot "${APPO[@]}" > /dev/null 2>&1; echo "create exit=$? cache=$(stat -f %z e16-$v-am3.aot 2>/dev/null)")
  echo "## 기준"
  probe base-noagent e16-$v-base.aot
  echo "## A: training 무agent → 운영 agent"
  probe A-noop  e16-$v-base.aot $NOOP
  probe A-xform e16-$v-base.aot $XF
  probe A-dd    e16-$v-base.aot $DD "${DDOPT[@]}"
  echo "## B: training agent → 운영 agent(같은 agent)"
  probe B-noop  e16-$v-trnoop.aot  $NOOP
  probe B-xform e16-$v-trxform.aot $XF
  probe B-dd    e16-$v-trdd.aot    $DD "${DDOPT[@]}"
  echo "## C: 세 단계 --add-modules=java.instrument(한 줄 방식 캐시), 운영에만 agent"
  probe C-noagent e16-$v-addmods.aot $AM
  probe C-noop    e16-$v-addmods.aot $AM $NOOP
  probe C-xform   e16-$v-addmods.aot $AM $XF
  probe C-dd      e16-$v-addmods.aot $AM $DD "${DDOPT[@]}"
  probe C-without-addmods e16-$v-addmods.aot
  echo "## C3: 3단계 --add-modules 캐시"
  probe C3-noop e16-$v-am3.aot $AM $NOOP
  echo "## D: training 무agent → 운영 native agent(can_generate_all_class_hook_events)"
  probe D-native e16-$v-base.aot $NAT
  probe D-native-addmods e16-$v-addmods.aot $AM $NAT
  echo "## Started 5회 중앙값"
  started5 base-noagent -XX:AOTCache=e16-$v-base.aot
  started5 A-dd -XX:AOTCache=e16-$v-base.aot $DD "${DDOPT[@]}"
  started5 B-dd -XX:AOTCache=e16-$v-trdd.aot $DD "${DDOPT[@]}"
  started5 C-dd -XX:AOTCache=e16-$v-addmods.aot $AM $DD "${DDOPT[@]}"
  started5 nocache-dd $DD "${DDOPT[@]}"
}

case ${1:?build|25|26|27} in
  build) build > "$RUNS/e16-build.log" 2>&1; cat "$RUNS/e16-build.log" | grep -v '^#   ' ;;
  *) run_jdk "$1" > "$RUNS/e16-agent-jdk$1.log" 2>&1; grep -v '^#   ' "$RUNS/e16-agent-jdk$1.log" | cut -c1-250 ;;
esac
