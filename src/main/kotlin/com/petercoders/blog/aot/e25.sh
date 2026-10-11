#!/usr/bin/env bash
# e25 — jar 가 바뀌어도 캐시가 버려지지 않던 패치 버전이 있는가(JDK-8377932).
#   JDK-8377932(Ioi Lam, 2026-02, 'AOT cache is not rejected when JAR file has changed'):
#   AOTMode=create(최종 정적 아카이브 덤프)가 AOTClassLocationConfig 의 _max_used_index 를 갱신하지 않아,
#   운영 기동의 app classpath 검사(need_to_check_app_classpath)가 통째로 빠졌다.
#   수정은 메인라인 27(b41ba3a4), backport 는 jdk25u 25.0.4 · jdk26u 26.0.2.
#   JDK 마다 고정 jar 를 새로 펼치고 onRefresh 로 캐시를 만든 뒤
#     (0) 그대로 AOTMode=on
#     (1) 앱 jar 만 touch(내용 그대로, mtime 만 바뀐다)
#     (2) 앱 jar mtime 을 되돌린 뒤 spring-core jar 에서 StringUtils.class 를 지운다(크기·mtime 이 바뀐다)
#   를 auto/on 으로 띄워 exit, class+load shared 수, StringUtils 의 source, -Xlog:class+path=info 의
#   'Archived app classpath validation' 줄이 찍히는지를 본다. (2)는 캐시 없이도 띄워 jar 가 실제로 깨졌는지 확인한다.
#   quiet: one 과 같되 training·운영 모두 -Dorg.springframework.boot.logging.LoggingSystem=…LogbackLoggingSystem 을 준다.
#          LoggingSystemFactory 조회를 건너뛰어 Log4J2LoggingSystem 을 아예 load 하지 않는 같은 앱이다(-D 는 캐시 검사 대상이 아니다).
#   hello: JBS 재현 절차 그대로(HelloWorld.jar 하나를 -cp 로, AOTCacheOutput → on → touch → on).
#   why  : Spring 앱이 수정 전 패치에서도 걸린 이유. 3단계 create 를 -Xlog:class+load 로 띄워, create 중에
#          AOT config 이미지가 아니라 app classpath(file:/jar:)에서 평소대로 load 된 클래스를 센다.
#          그런 클래스가 하나라도 있으면 ClassLoader::record_result() 가 _max_used_index 를 올려
#          need_to_check_app_classpath() 가 참이 된다(jdk-25-ga aotClassLocation.hpp 198~200행).
# 사용: JDKS=<Temurin 들을 푼 폴더> L=runs/e25-jar-check-by-patch.log
#       bash e25.sh header > $L; for j in T02 T03 T41 J25 J26; do bash e25.sh one $j >> $L; done
#       for j in T02 T03 T41 J25 J26; do bash e25.sh hello $j >> $L; done
#       for j in T03 T41; do bash e25.sh why $j >> $L; bash e25.sh whyq $j >> $L; done; for j in T02 T03 T41 J25 J26; do bash e25.sh quiet $j >> $L; done
source "$(dirname "$0")/r2-env.sh"
JDKS=${JDKS:?JDKS 필요}
T41=$(ls -d "$JDKS"/tem25041-arm/*/Contents/Home)/bin/java
T03=$(ls -d "$JDKS"/tem2503-arm/*/Contents/Home)/bin/java
T02=$(ls -d "$JDKS"/tem2502-arm/*/Contents/Home)/bin/java
LIB=application/lib/spring-core-7.0.9.jar
CLS=org/springframework/util/StringUtils.class
APPO=(-jar application/$APPJAR)
LOGSYS=-Dorg.springframework.boot.logging.LoggingSystem=org.springframework.boot.logging.logback.LogbackLoggingSystem

aotlines() { grep -E '\[(warning|error) *\]\[aot' "$1" | sed -E 's/^\[[0-9.]+s\]//' | head -3 | sed 's/^/    /'; }
validation() { "$1" -XX:AOTCache=c.aot -Xlog:class+path=info "${APPO[@]}" 2>&1 | grep -oE 'Archived (boot|app) classpath validation: [a-z]+' | tr '\n' ' '; }
su_source() { grep -E '^org\.springframework\.util\.StringUtils source:' "$1" | head -1 | sed 's/.*source: //'; }

case ${1:?header|one|quiet|hello|why|whyq} in
header)
  header e25-jar-check-by-patch "jar 를 바꾼 뒤 캐시가 버려지는가, JDK 패치 버전별(JDK-8377932)" "$T02" "$T03" "$T41" "$J25" "$J26"
  echo "# 조건마다 runs/work-r2/e25/<라벨>/ 에 고정 jar 를 새로 펼치고(-Djarmode=tools extract) onRefresh 로 캐시 c.aot 를 만든다"
  ;;
one|quiet)
  label=${2:?T02|T03|T41|J25|J26}
  tag=""; if [ "$1" = quiet ]; then tag="quiet-"; APPO=("$LOGSYS" "${APPO[@]}"); fi
  J=${!label}
  v=$("$J" -version 2>&1 | sed -n 2p)
  W=$R2/e25/$tag$label; rm -rf "$W"; mkdir -p "$W"; cd "$W" || exit 1
  "$J" -Djarmode=tools -jar "$PIN" extract --destination application > /dev/null 2>&1
  "$J" -XX:AOTCacheOutput=c.aot -Dspring.context.exit=onRefresh "${APPO[@]}" > train.out 2>&1; trc=$?
  echo "## [$tag$label] $v"
  [ -n "$tag" ] && echo "  training·운영 모두 $LOGSYS"
  echo "  training+assembly exit=$trc size=$(stat -f %z c.aot 2>/dev/null)"
  "$J" -XX:AOTCache=c.aot -XX:AOTMode=on "${APPO[@]}" > o0.txt 2>&1; echo "  (0) 그대로       on exit=$? · $(validation "$J")"
  touch -r application/$APPJAR app.stamp
  touch application/$APPJAR
  "$J" -XX:AOTCache=c.aot -Xlog:class+load:file=cl1.log:none "${APPO[@]}" > o1.txt 2>&1; rc=$?
  "$J" -XX:AOTCache=c.aot -XX:AOTMode=on "${APPO[@]}" > o1on.txt 2>&1; rcon=$?
  echo "  (1) 앱 jar touch  auto exit=$rc $(count_sources cl1.log) · on exit=$rcon · $(validation "$J")"
  aotlines o1on.txt
  touch -r app.stamp application/$APPJAR
  "$J" -XX:AOTCache=c.aot -XX:AOTMode=on "${APPO[@]}" > o1r.txt 2>&1; echo "  (1') mtime 되돌림 on exit=$?"
  before=$(stat -f %z $LIB)
  zip -q -d $LIB $CLS
  "$J" "${APPO[@]}" > o2nc.txt 2>&1; rcnc=$?
  "$J" -XX:AOTCache=c.aot -Xlog:class+load:file=cl2.log:none "${APPO[@]}" > o2.txt 2>&1; rc=$?
  "$J" -XX:AOTCache=c.aot -XX:AOTMode=on "${APPO[@]}" > o2on.txt 2>&1; rcon=$?
  echo "  (2) StringUtils.class 삭제(spring-core $before → $(stat -f %z $LIB) B)"
  echo "      캐시 없이 exit=$rcnc $(grep -oE 'NoClassDefFoundError: [A-Za-z/]+' o2nc.txt | head -1)"
  echo "      auto exit=$rc $(count_sources cl2.log) · StringUtils source: $(su_source cl2.log) · Started: $(grep -c 'Started ' o2.txt)"
  echo "      on exit=$rcon · $(validation "$J")"
  aotlines o2on.txt
  ;;
hello)
  label=${2:?T02|T03|T41|J25|J26}
  J=${!label}; JC=$(dirname "$J")/javac; JAR=$(dirname "$J")/jar
  v=$("$J" -version 2>&1 | sed -n 2p)
  W=$R2/e25/hello-$label; rm -rf "$W"; mkdir -p "$W/src"; cd "$W" || exit 1
  printf 'public class HelloWorld { public static void main(String[] a) { System.out.println("Hello World"); } }\n' > src/HelloWorld.java
  "$JC" -d cls src/HelloWorld.java && "$JAR" cf HelloWorld.jar -C cls .
  # st_mtime 은 초 단위라, 만들고 1초 안에 touch 하면 mtime 이 같아 검사를 통과한다. 만든 시각을 과거로 고정해 둔다.
  touch -t 202601010000.00 HelloWorld.jar
  hv() { "$J" -cp HelloWorld.jar -XX:AOTCache=hw.aot -Xlog:class+path=info HelloWorld 2>&1 | grep -oE 'Archived (boot|app) classpath validation: [a-z]+' | tr '\n' ' '; }
  "$J" -cp HelloWorld.jar -XX:AOTCacheOutput=hw.aot HelloWorld > train.out 2>&1; trc=$?
  echo "## [hello $label] $v"
  echo "  -cp HelloWorld.jar(mtime $(stat -f %Sm -t '%Y-%m-%d %H:%M:%S' HelloWorld.jar)), training+assembly exit=$trc size=$(stat -f %z hw.aot 2>/dev/null)"
  "$J" -cp HelloWorld.jar -XX:AOTCache=hw.aot -XX:AOTMode=on HelloWorld > h0.txt 2>&1; echo "  (0) 그대로      on exit=$? · validation: $(hv)"
  touch HelloWorld.jar; echo "  touch 뒤 mtime $(stat -f %Sm -t '%Y-%m-%d %H:%M:%S' HelloWorld.jar)"
  "$J" -cp HelloWorld.jar -XX:AOTCache=hw.aot -XX:AOTMode=on HelloWorld > h1.txt 2>&1; rcon=$?
  "$J" -cp HelloWorld.jar -XX:AOTCache=hw.aot -Xlog:class+load:file=hcl.log:none HelloWorld > h1a.txt 2>&1; rc=$?
  echo "  (1) jar touch   on exit=$rcon · auto exit=$rc HelloWorld source: $(grep -E '^HelloWorld source:' hcl.log | sed 's/.*source: //') · validation: $(hv)"
  aotlines h1.txt
  ;;
why|whyq)
  label=${2:?T03|T41}
  tag=why; if [ "$1" = whyq ]; then tag=whyq; APPO=("$LOGSYS" "${APPO[@]}"); fi
  J=${!label}
  v=$("$J" -version 2>&1 | sed -n 2p)
  W=$R2/e25/$tag-$label; rm -rf "$W"; mkdir -p "$W"; cd "$W" || exit 1
  "$J" -Djarmode=tools -jar "$PIN" extract --destination application > /dev/null 2>&1
  "$J" -XX:AOTMode=record -XX:AOTConfiguration=w.conf -Dspring.context.exit=onRefresh "${APPO[@]}" > rec.out 2>&1; rrc=$?
  "$J" -XX:AOTMode=create -XX:AOTConfiguration=w.conf -XX:AOTCache=w.aot -Xlog:class+load:file=create-cl.log:none "${APPO[@]}" > create.out 2>&1; crc=$?
  echo "## [$tag $label] $v"
  [ "$tag" = whyq ] && echo "  record·create 모두 $LOGSYS"
  echo "  record exit=$rrc · create exit=$crc · create 중 class+load $(count_sources create-cl.log)"
  echo "  create 중 app classpath(file:/jar:)에서 평소대로 load 된 클래스 $(grep -cE 'source: (file:|jar:)' create-cl.log)개:"
  grep -E 'source: (file:|jar:)' create-cl.log | sed -E 's#source: .*/#source: #' | sed 's/^/    /'
  ;;
*) echo "header|one|quiet|hello|why|whyq"; exit 2;;
esac
