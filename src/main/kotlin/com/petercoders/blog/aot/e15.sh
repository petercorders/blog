#!/usr/bin/env bash
# e15 — training 과 운영의 GC·Heap·헤더 옵션이 다를 때 캐시가 쓰이는가(JDK 25.0.4.1 / 26.0.2.1).
#   캐시 셋: 기본(G1, 옵션 없음) / -XX:+UseSerialGC / -XX:G1HeapRegionSize=8m 로 training 한 것
#   운영 옵션마다 auto 와 on 으로 한 번씩 띄워 exit, 기본 레벨 [aot] 경고 줄, class+load shared 수를 남긴다.
#   docker 데몬은 이 계정에서 쓸 수 없어서(소켓 권한 거부) 컨테이너 한도는
#   -XX:ActiveProcessorCount=1 -XX:MaxRAM=512m 로 ergonomics 를 흉내 냈다(흉내라는 점을 로그에 남긴다).
# 사용: e15.sh 25|26 [extra]
#   extra: ZGC 로 training 한 캐시, -XX:+AOTStreamableObjects(JDK 26) 캐시, 그리고 거부될 때 -Xlog:aot 가 대는 이유
source "$(dirname "$0")/r2-env.sh"
v=${1:?25|26}
mode=${2:-grid}
J=$J25; [ "$v" = 26 ] && J=$J26
D=$R2/dirA
APPOPT=(-jar application/$APPJAR)

mk() { # mk <cache> <training 옵션...>
  local c=$1; shift
  if [ ! -f "$D/$c" ]; then
    (cd "$D" && "$J" "$@" -XX:AOTCacheOutput="$c" -Dspring.context.exit=onRefresh "${APPOPT[@]}" > "train-$c.log" 2>&1)
  fi
  echo "# cache $c: training 옵션 [$*] size=$(stat -f %z "$D/$c")"
}

probe() { # probe <cache> <운영 옵션...>
  local c=$1; shift
  local tag="[$*]"; [ $# -eq 0 ] && tag="[기본]"
  (cd "$D"
    "$J" "$@" -XX:AOTCache="$c" -Xlog:class+load:file=cl-e15.log:none "${APPOPT[@]}" > out.txt 2> err.txt; rc=$?
    local shared total gcsel
    shared=$(grep -c 'source: shared objects file' cl-e15.log); total=$(grep -c 'source:' cl-e15.log)
    "$J" "$@" -XX:AOTCache="$c" -XX:AOTMode=on "${APPOPT[@]}" > out-on.txt 2> err-on.txt; rcon=$?
    gcsel=$("$J" "$@" -XX:+PrintFlagsFinal -version 2>/dev/null | awk '$2 ~ /^Use(Serial|Parallel|G1|Z|Shenandoah)GC$/ && $4=="true" {print $2}' | tr '\n' ' ')
    echo "cache=$c run=$tag gc=$gcsel auto_exit=$rc shared=$shared/$total on_exit=$rcon stderr_lines=$(wc -l < err.txt | tr -d ' ')"
    grep -E '\]\[aot' out.txt | sed 's/^\[[0-9.]*s\]/    auto:/' | head -4
    grep -E '\]\[aot' out-on.txt | sed 's/^\[[0-9.]*s\]/    on  :/' | head -4
    grep -hE 'Error occurred|Unable to use|^Error' out-on.txt err-on.txt | sed 's/^/    on  : /' | head -2)
}

why() { # why <cache> <운영 옵션...> — 거부 이유를 -Xlog:aot(info) 로 본다
  local c=$1; shift
  (cd "$D"; "$J" "$@" -XX:AOTCache="$c" -Xlog:aot -XX:AOTMode=on "${APPOPT[@]}" 2>&1 \
     | grep -iE 'mismatch|differ|not (the )?same|ZGC|compress|object|heap|disabled|cannot|unable|requires|support|streamed|mapped|header' \
     | grep -vE 'Skipping|^\s*$' | head -6 | sed -E 's/^\[[0-9.]+s\]/    why:/')
}

if [ "$mode" = extra ]; then
  header "e15-jdk$v-extra" "ZGC·streamable 캐시와 거부 이유" "$J"
  echo "## 거부 이유(-Xlog:aot, AOTMode=on) — G1 으로 만든 app$v.aot"
  for o in -XX:+UseZGC -XX:-UseCompressedOops -Xmx40g -XX:+UseCompactObjectHeaders; do echo "### 운영 $o"; why app$v.aot $o; done
  echo "## ZGC 로 training 한 캐시"
  mk app$v-zgc.aot -XX:+UseZGC
  (cd "$D" && grep -iE 'heap|object|stream|archiv.*(disabled|not)' "train-app$v-zgc.aot.log" | head -5 | sed 's/^/    train: /')
  probe app$v-zgc.aot -XX:+UseZGC
  (cd "$D"; "$J" -XX:+UseZGC -XX:AOTCache=app$v-zgc.aot -Xlog:aot "${APPOPT[@]}" 2>&1 | grep -E 'created with UseCompressedOops|AOT-linked classes|full module graph:|heap' | grep -v initial | head -5 | sed -E 's/^\[[0-9.]+s\]/    zgc-run info:/')
  echo "    (대조) G1 캐시를 G1 로: $(cd "$D"; "$J" -XX:AOTCache=app$v.aot -Xlog:aot "${APPOPT[@]}" 2>&1 | grep -E 'AOT-linked classes' | head -1 | sed -E 's/^\[[0-9.]+s\]//')"
  probe app$v-zgc.aot
  probe app$v-zgc.aot -XX:+UseSerialGC
  if [ "$v" != 25 ]; then
    echo "## G1 + -XX:+AOTStreamableObjects 로 training 한 캐시(JEP 516: 압축 포인터를 써도 streamable 형식으로)"
    mk app$v-stream.aot -XX:+UnlockDiagnosticVMOptions -XX:+AOTStreamableObjects
    probe app$v-stream.aot -XX:+UseZGC
    probe app$v-stream.aot
    echo "## -Xmx40g(압축 포인터 꺼짐)로 training 한 캐시"
    mk app$v-x40g.aot -Xmx40g
    probe app$v-x40g.aot -XX:+UseZGC
    probe app$v-x40g.aot -Xmx40g
    probe app$v-x40g.aot
  fi
  echo "## 운영 옵션 ZGC 로 G1 캐시를 썼을 때 stderr 1줄 여부 확인(컨테이너 흉내 행의 stderr 1줄 내용)"
  (cd "$D"; "$J" -XX:ActiveProcessorCount=1 -XX:MaxRAM=512m -XX:AOTCache=app$v.aot "${APPOPT[@]}" 2>&1 >/dev/null | head -2 | sed 's/^/    stderr: /')
  exit 0
fi

header "e15-jdk$v" "training·운영 옵션 불일치 격자" "$J"
mk app$v.aot
mk app$v-serial.aot -XX:+UseSerialGC
mk app$v-r8m.aot -XX:G1HeapRegionSize=8m
echo "# 이 기기에서 옵션 없이 고른 GC·Heap: $("$J" -XX:+PrintFlagsFinal -version 2>/dev/null | awk '$2 ~ /^(UseG1GC|MaxHeapSize|G1HeapRegionSize|UseCompressedOops)$/ {printf "%s=%s ", $2, $4}')"
echo "# command: (cd dirA && java <운영 옵션> -XX:AOTCache=<cache> [-XX:AOTMode=on] -jar application/$APPJAR)"
echo "## G1(기본)로 만든 캐시"
probe app$v.aot
probe app$v.aot -XX:+UseSerialGC
probe app$v.aot -XX:+UseParallelGC
probe app$v.aot -XX:+UseZGC
probe app$v.aot -Xmx256m
probe app$v.aot -Xmx4g
probe app$v.aot -XX:G1HeapRegionSize=16m
probe app$v.aot -XX:-UseCompressedOops
probe app$v.aot -Xmx40g
probe app$v.aot -XX:+UseCompactObjectHeaders
echo "## 컨테이너 한도 흉내(docker 대신): CPU 1개·메모리 512MB 로 ergonomics 를 바꾼다"
probe app$v.aot -XX:ActiveProcessorCount=1 -XX:MaxRAM=512m
probe app$v.aot -XX:ActiveProcessorCount=4 -XX:MaxRAM=4g
echo "## Serial 로 만든 캐시(작은 CI 러너에서 training 했다는 상황)"
probe app$v-serial.aot
probe app$v-serial.aot -XX:+UseSerialGC
probe app$v-serial.aot -XX:+UseZGC
echo "## G1HeapRegionSize=8m 로 만든 캐시(phase 오버레이: training 8m → 운영 16m)"
probe app$v-r8m.aot -XX:G1HeapRegionSize=8m
probe app$v-r8m.aot -XX:G1HeapRegionSize=16m
probe app$v-r8m.aot
