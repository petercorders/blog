#!/usr/bin/env bash
# 사용: setup.sh
# fat jar 를 펼치고(runs/work/application) JDK 25 로 AOT 캐시(blog.aot)를 3단계로 만든다. create 단계에 aot+map 덤프를 함께 남긴다.
# 이미 있으면 다시 만들지 않는다. 다시 만들려면 runs/work 를 지운다.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../../../../.." && pwd)
J25=${J25:-$HOME/Library/Java/JavaVirtualMachines/ms-25.0.4.1/Contents/Home/bin/java}
JAR=$REPO/build/libs/blog-0.0.1-SNAPSHOT.jar
W=$HERE/runs/work; mkdir -p "$W"; cd "$W"
[ -d application ] || "$J25" -Djarmode=tools -jar "$JAR" extract --destination application
APP=application/blog-0.0.1-SNAPSHOT.jar
if [ ! -f blog.aot ]; then
  "$J25" -XX:AOTMode=record -XX:AOTConfiguration=blog.aotconf -jar "$APP" > record.log 2>&1
  "$J25" -XX:AOTMode=create -XX:AOTConfiguration=blog.aotconf -XX:AOTCache=blog.aot \
     -Xlog:aot=info:file=create.log:none -Xlog:aot+map=trace:file=blog.aot.map:none:filesize=0 -jar "$APP"
fi
if [ ! -f blog.jsa ]; then
  "$J25" -XX:ArchiveClassesAtExit=blog.jsa -jar "$APP" > jsa.log 2>&1   # JEP 350 동적 아카이브, CDS 와의 거리 절 비교용
fi
ls -l blog.aotconf blog.aot blog.aot.map blog.jsa
echo "APP=$W/$APP"; echo "AOT=$W/blog.aot"; echo "JSA=$W/blog.jsa"
