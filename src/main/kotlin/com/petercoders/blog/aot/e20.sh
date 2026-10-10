#!/usr/bin/env bash
# e20 — JDK 21(Temurin 21.0.12.1+1-LTS)에서 쓸 수 있는 것: 펼친 배치 + 동적 CDS.
#   a  -XX:ArchiveClassesAtExit=app21.jsa -Dspring.context.exit=onRefresh 로 동적 CDS 를 만들고,
#      -XX:AOTCacheOutput 이 거부되는 출력을 남긴다
#   b  F0(fat jar)/X0(-Xshare:off)/E0(기본 CDS)/E3(app21.jsa) 라운드로빈
#   고정 jar 는 class file 69(Java 25)라 JDK 21 이 읽지 못한다(UnsupportedClassVersionError, runs/e20-a 첫 시도).
#   그래서 f3c9bfb 트리에서 Java toolchain 만 21 로 바꾼 변형(variants/e20-jdk21)을 빌드해 쓴다. 의존성은 같다.
# 사용: J21=<java> e20.sh prep|a|b
source "$(dirname "$0")/r2-env.sh"
step=${1:?prep|a|b}
J=${J21:?J21 환경변수 필요}
F=$R2/jdk21/fat; A=$R2/jdk21/ext
ROUNDS=${ROUNDS:-12}
case $step in
prep)
  rm -rf "$R2/jdk21"; mkdir -p "$F" "$A"
  cp -p /tmp/gradle-aot-e20-jdk21/build/libs/$APPJAR "$F/"
  (cd "$A" && "$J" -Djarmode=tools -jar "$F/$APPJAR" extract --destination application > /dev/null && echo "extract exit=$?")
  echo "# jdk21 변형 jar: sha256 $(shasum -a 256 "$F/$APPJAR" | cut -c1-64) size $(stat -f %z "$F/$APPJAR"), class file major $(unzip -p "$F/$APPJAR" BOOT-INF/classes/com/petercoders/blog/BlogApplication.class | xxd -s 6 -l 2 -p) (0x41=65)"
  ;;
a)
  header e20-a "JDK 21 동적 CDS 만들기와 AOT 플래그 거부" "$J"
  echo "## -XX:AOTCacheOutput"
  (cd "$A"; "$J" -XX:AOTCacheOutput=x.aot -jar application/$APPJAR > out.txt 2>&1; echo "exit=$?"; head -3 out.txt | sed 's/^/    /')
  echo "## -XX:AOTCache"
  (cd "$A"; "$J" -XX:AOTCache=x.aot -jar application/$APPJAR > out.txt 2>&1; echo "exit=$?"; head -3 out.txt | sed 's/^/    /')
  echo "## -XX:AOTMode=record"
  (cd "$A"; "$J" -XX:AOTMode=record -XX:AOTConfiguration=x.conf -jar application/$APPJAR > out.txt 2>&1; echo "exit=$?"; head -3 out.txt | sed 's/^/    /')
  echo "## 동적 CDS: java -XX:ArchiveClassesAtExit=app21.jsa -Dspring.context.exit=onRefresh -jar application/$APPJAR"
  (cd "$A"; rm -f app21.jsa; t0=$(now_ms); "$J" -XX:ArchiveClassesAtExit=app21.jsa -Dspring.context.exit=onRefresh -jar application/$APPJAR > jsa21.log 2>&1; rc=$?; t1=$(now_ms)
   echo "exit=$rc wall_ms=$((t1-t0)) size=$(stat -f %z app21.jsa 2>/dev/null || echo 없음)"; grep -E 'warning|error|Dumping|Written dynamic' jsa21.log | head -5 | cut -c1-200 | sed 's/^/    /')
  (cd "$A"; "$J" -XX:SharedArchiveFile=app21.jsa -Xlog:class+load:file=cl21.log:none -jar application/$APPJAR > /dev/null 2>&1; echo "E3 exit=$? $(count_sources cl21.log)")
  (cd "$A"; "$J" -Xlog:class+load:file=cl21-e0.log:none -jar application/$APPJAR > /dev/null 2>&1; echo "E0 exit=$? $(count_sources cl21-e0.log)")
  ;;
b)
  header e20-b "JDK 21 라운드로빈 ${ROUNDS}회" "$J"
  conds=(F0 X0 E0 E3)
  for r in $(seq 1 "$ROUNDS"); do
    echo "## round $r uptime: $(uptime | sed 's/.*load averages*: //')"
    off=$(( (r-1) % 4 ))
    for k in 0 1 2 3; do
      c=${conds[$(( (k+off) % 4 ))]}
      case $c in
        F0) (cd "$F" && run_once "F0 r=$r" "$J" -jar $APPJAR);;
        X0) (cd "$A" && run_once "X0 r=$r" "$J" -Xshare:off -jar application/$APPJAR);;
        E0) (cd "$A" && run_once "E0 r=$r" "$J" -jar application/$APPJAR);;
        E3) (cd "$A" && run_once "E3 r=$r" "$J" -XX:SharedArchiveFile=app21.jsa -jar application/$APPJAR);;
      esac
    done
  done
  ;;
esac
