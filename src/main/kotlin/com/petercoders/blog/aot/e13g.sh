#!/usr/bin/env bash
# e13-g — 새 실행 없이 이미 남은 class+load 원본을 source 별로 다시 센다.
#   (1) e3 의 fat jar 무캐시 class+load(runs/fatjar-classload.log, 5,804줄): 기본 CDS 가 덮는 줄과 덮지 않는 줄의 내역
#       e3 로그의 'source: 접두어 분포'는 sed 패턴이 [a-z]+ 라 __JVM_LookupDefineClass__·__dynamic_proxy__ 줄을 묶지 못했다.
#       hidden class(lambda·TypeSwitch)는 source 칸에 호스트 클래스 이름이 찍혀 ch·java·kotlin·org·sun 처럼 흩어져 보였다.
#   (2) e13-b 의 펼친 배치 + 캐시(E1) class+load(runs/work-r2/classload-e13-E1.log, 5,802줄): 캐시에서 온 5,604줄의 구성
#       (기본 CDS 밖 JDK 클래스와 hidden class 도 캐시에 실리는가)
# 사용: bash e13g.sh > runs/e13-g-source-breakdown.log
source "$(dirname "$0")/r2-env.sh"
E3=$RUNS/fatjar-classload.log
E1=$R2/classload-e13-E1.log
F0=$R2/classload-e13-F0.log
JDKRE='^(java|javax|jdk|sun|com\.sun)\.'
HIDRE='\$\$Lambda|\$\$TypeSwitch|LambdaForm\$|/0x'

echo "# experiment: e13-g — class+load 원본의 source 별 내역(새 실행 없음, e3·e13-b 원본 재집계)"
echo "# date: $(date '+%Y-%m-%d %H:%M:%S %z')"
echo "# inputs: $(basename "$E3") ($(wc -l < "$E3" | tr -d ' ')줄, 데코레이션 있음), work-r2/$(basename "$E1") ($(wc -l < "$E1" | tr -d ' ')줄), work-r2/$(basename "$F0") ($(wc -l < "$F0" | tr -d ' ')줄)"
echo

breakdown() {  # breakdown <file> <label>
  local f=$1 label=$2 total shared nested jrt file lookup proxy rest restl
  total=$(wc -l < "$f" | tr -d ' ')
  shared=$(grep -c 'source: shared objects file' "$f")
  nested=$(grep -c 'source: jar:nested:' "$f")
  jrt=$(grep -c 'source: jrt:/' "$f")
  file=$(grep -c 'source: file:' "$f")
  lookup=$(grep -c 'source: __JVM_LookupDefineClass__' "$f")
  proxy=$(grep -c 'source: __dynamic_proxy__' "$f")
  rest=$(grep -vcE 'source: (shared objects file|jar:|jrt:/|file:|__JVM_LookupDefineClass__|__dynamic_proxy__)' "$f")
  restl=$(grep -vE 'source: (shared objects file|jar:|jrt:/|file:|__JVM_LookupDefineClass__|__dynamic_proxy__)' "$f" | grep -cE "$HIDRE")
  echo "## $label"
  echo "total=$total"
  echo "  shared objects file (기본 CDS 또는 AOT Cache) = $shared"
  echo "  jar:nested: (fat jar 안 BOOT-INF/lib·classes)    = $nested"
  echo "  jrt:/ (기본 CDS 에 없는 JDK 클래스)              = $jrt"
  echo "  file: (Spring Boot launcher 등)                  = $file"
  echo "  __JVM_LookupDefineClass__ (LambdaForm\$MH 등)    = $lookup"
  echo "  __dynamic_proxy__                                = $proxy"
  echo "  source=호스트 클래스 이름 (lambda·TypeSwitch)    = $rest (그중 \$\$Lambda·\$\$TypeSwitch·/0x 이름 $restl)"
  echo "  합 = $((shared+nested+jrt+file+lookup+proxy+rest))"
  echo "  shared 가 아닌 줄 = $((total-shared)) = jar:nested $nested + jrt $jrt + file $file + 실행 중 생성 $((lookup+proxy+rest)) (lookup $lookup + proxy $proxy + 호스트명 $rest)"
}

breakdown "$E3" "(1) e3 fat jar 무캐시 (fatjar-classload.log)"
echo "  shared 중 JDK 클래스(java·javax·jdk·sun·com.sun) = $(grep 'source: shared objects file' "$E3" | awk '{print $2}' | grep -cE "$JDKRE")"
echo "  file: 중 org.springframework.boot.loader.*       = $(grep 'source: file:' "$E3" | awk '{print $2}' | grep -c '^org\.springframework\.boot\.loader\.')"
echo "  jrt:/ 모듈별 상위 5:"
grep 'source: jrt:/' "$E3" | sed -E 's/.*source: jrt:\/([^ ]*).*/\1/' | sort | uniq -c | sort -rn | head -5 | sed 's/^/    /'
echo
breakdown "$F0" "(1') e13-b F0 fat jar 무캐시 (classload-e13-F0.log, 데코레이션 없음)"
echo
echo "## (2) e13-b E1 펼친 배치 + 캐시 (classload-e13-E1.log, 데코레이션 없음 — 클래스 이름이 첫 칸)"
echo "total=$(wc -l < "$E1" | tr -d ' ') shared=$(grep -c 'source: shared objects file' "$E1")"
echo "  shared 중 JDK 클래스(java·javax·jdk·sun·com.sun, hidden 제외) = $(grep 'source: shared objects file' "$E1" | awk '{print $1}' | grep -vE "$HIDRE" | grep -cE "$JDKRE")  (e3 기본 CDS 의 JDK 클래스 1,379 와 대조)"
echo "  shared 중 hidden class(\$\$Lambda·\$\$TypeSwitch·LambdaForm\$·/0x) = $(grep 'source: shared objects file' "$E1" | awk '{print $1}' | grep -cE "$HIDRE")"
echo "  shared 중 그 밖(앱 클래스패스 등)                    = $(grep 'source: shared objects file' "$E1" | awk '{print $1}' | grep -vE "$JDKRE" | grep -vcE "$HIDRE")"
