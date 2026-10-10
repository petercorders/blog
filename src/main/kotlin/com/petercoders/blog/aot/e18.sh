#!/usr/bin/env bash
# e18 — 동적 CDS(-XX:SharedArchiveFile)와 AOT Cache(-XX:AOTCache)를 함께 주면 어떻게 되나(JDK 25.0.4.1 / 26.0.2.1).
# 각 JDK 로 만든 펼친 배치 캐시(app25.aot / app26.aot)와 같은 배치의 동적 CDS(app25.jsa / app26.jsa)를 쓴다.
source "$(dirname "$0")/r2-env.sh"
D=$R2/dirA
header e18 "SharedArchiveFile + AOTCache 동시 지정" "$J25" "$J26"
for v in 25 26; do
  J=$J25; [ $v = 26 ] && J=$J26
  if [ ! -f "$D/app$v.jsa" ]; then
    (cd "$D" && "$J" -XX:ArchiveClassesAtExit=app$v.jsa -Dspring.context.exit=onRefresh -jar application/$APPJAR > jsa$v.log 2>&1)
  fi
  echo "## JDK $v — app$v.jsa size=$(stat -f %z "$D/app$v.jsa") / app$v.aot size=$(stat -f %z "$D/app$v.aot")"
  run() {
    (cd "$D"; "$J" "$@" -Xlog:class+load:file=cl-e18.log:none -jar application/$APPJAR > out.txt 2> err.txt; rc=$?
     echo "### java $* -jar …  → exit=$rc shared=$(grep -c 'source: shared objects file' cl-e18.log 2>/dev/null)/$(grep -c 'source:' cl-e18.log 2>/dev/null) stderr_lines=$(wc -l < err.txt | tr -d ' ')"
     echo "--- stdout(Spring 로그 제외) ---"; grep -vE '^ *$|^  \.|^ /\\\\|^\( \(|^ \\\\/|^  '"'"'|^ =+|:: Spring Boot ::|INFO [0-9]+ ---' out.txt | head -8
     echo "--- stderr ---"; head -8 err.txt
     grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' out.txt)
  }
  run -XX:SharedArchiveFile=app$v.jsa
  run -XX:AOTCache=app$v.aot
  run -XX:SharedArchiveFile=app$v.jsa -XX:AOTCache=app$v.aot
  run -XX:SharedArchiveFile=app$v.jsa -XX:AOTCache=app$v.aot -XX:AOTMode=on
  run -Xshare:on -XX:AOTCache=app$v.aot
  run -Xshare:off -XX:AOTCache=app$v.aot
done
