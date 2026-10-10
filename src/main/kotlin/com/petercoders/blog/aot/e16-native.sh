#!/usr/bin/env bash
# e16-native — native agent 가 AOT Cache 를 끄는 조건(JDK 25.0.4.1 / 26.0.2.1 / 27 GA).
#   e16 D 에서 can_generate_all_class_hook_events 권한만 요청한 agent 는 세 JDK 모두 캐시를 끄지 않았다.
#   여기서는 ClassFileLoadHook 이벤트까지 켠 경우(-agentpath:…=cflh)를 같은 base 캐시로 auto/on 비교한다.
# 사용: J27=<java> e16-native.sh
source "$(dirname "$0")/r2-env.sh"
AG=$R2/agents; D=$R2/dirA
header e16-native "native agent: 권한만 vs ClassFileLoadHook 이벤트까지" "$J25" "$J26" "${J27:?J27 필요}"
for v in 25 26 27; do
  J=$J25; [ $v = 26 ] && J=$J26; [ $v = 27 ] && J=$J27
  JH=${J%/bin/java}
  clang -shared -fPIC -I"$JH/include" -I"$JH/include/darwin" -o "$AG/libe16native-$v.dylib" "$AOT/e16/e16_native.c" || exit 1
  for opt in "" "=cflh"; do
    (cd "$D"
     "$J" -agentpath:$AG/libe16native-$v.dylib$opt -XX:AOTCache=e16-$v-base.aot -Xlog:class+load:file=cl-e16n.log:none -jar application/$APPJAR > out.txt 2>&1; rc=$?
     "$J" -agentpath:$AG/libe16native-$v.dylib$opt -XX:AOTCache=e16-$v-base.aot -XX:AOTMode=on -jar application/$APPJAR > out-on.txt 2>&1; rcon=$?
     echo "[JDK $v agent${opt:-(권한만)}] auto exit=$rc $(count_sources cl-e16n.log) on exit=$rcon"
     grep -hE '\[e16\]' out.txt | sed 's/^/    agent: /'
     grep -hE '\]\[aot' out.txt | sed -E 's/^\[[0-9.]+s\]/    auto:/' | head -3
     grep -hE '\]\[aot|Error occurred|Unable to use' out-on.txt | sed -E 's/^\[[0-9.]+s\]/    on  :/' | head -3)
  done
done
