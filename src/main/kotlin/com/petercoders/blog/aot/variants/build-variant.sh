#!/usr/bin/env bash
# 변형 앱 빌드(e14·e22). 메인 작업 트리와 커밋은 건드리지 않는다.
#   1) git archive f3c9bfb 로 글의 jar 를 만든 트리를 /tmp/gradle-aot-<name>/ 에 꺼낸다
#   2) variants/<name>/overlay/ 의 *.kt.txt 를 같은 경로의 *.kt 로 복사한다
#   3) build.gradle.kts 에 variants/<name>/deps.txt 의 의존성 줄을 spring-boot-starter 다음에 넣는다
#      그리고 kco/ tip/ jkc/ 를 컴파일 대상에서 뺀다(f3c9bfb 트리에서도 Gradle 로는 컴파일되지 않던 코루틴 단독 파일.
#      지금 HEAD 의 build.gradle.kts 가 같은 이유로 빼고 있다. 앱에는 들어가지 않는 파일이다)
#   4) ./gradlew --offline -q bootJar 후 jar 를 펼친다(-Djarmode=tools extract)
# 사용: build-variant.sh <name> prepare|build|extract
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../../../../../.." && pwd)
name=$1; step=$2
DST=/tmp/gradle-aot-$name
J25=${J25:-$HOME/Library/Java/JavaVirtualMachines/ms-25.0.4.1/Contents/Home/bin/java}
case $step in
  prepare)
    rm -rf "$DST"; mkdir -p "$DST"
    (cd "$REPO" && git archive f3c9bfb) | tar -x -C "$DST"
    if [ -d "$HERE/$name/overlay" ]; then
      (cd "$HERE/$name/overlay" && find . -name '*.kt.txt') | while read -r f; do
        mkdir -p "$DST/$(dirname "$f")"; cp "$HERE/$name/overlay/$f" "$DST/${f%.txt}"
      done
    fi
    # variants/<name>/toolchain.txt 가 있으면 Java toolchain 버전만 바꾼다(e20: JDK 21 에서 돌리려면 class file 65 로 빌드해야 한다)
    if [ -f "$HERE/$name/toolchain.txt" ]; then
      tc=$(grep -v '^#' "$HERE/$name/toolchain.txt" | tr -d ' \n')
      sed -i '' "s/JavaLanguageVersion.of(25)/JavaLanguageVersion.of($tc)/" "$DST/build.gradle.kts"
    fi
    python3 - "$DST/build.gradle.kts" "$HERE/$name/deps.txt" <<'PY'
import sys, os
p, depf = sys.argv[1], sys.argv[2]
s = open(p).read()
anchor = '    implementation("org.springframework.boot:spring-boot-starter")\n'
assert anchor in s
deps = open(depf).read() if os.path.exists(depf) else ''
s = s.replace(anchor, anchor + ''.join('    ' + l.strip() + '\n' for l in deps.splitlines() if l.strip() and not l.startswith('#')))
s += '\n// build-variant.sh: kco/ tip/ jkc/ 는 kotlinx-coroutines 없이 컴파일되지 않는 단독 실험 파일이라 뺀다\nsourceSets.main { kotlin.exclude("com/petercoders/blog/kco/**", "com/petercoders/blog/tip/**", "com/petercoders/blog/jkc/**") }\n'
open(p, 'w').write(s)
PY
    echo "prepared $DST"; diff <(cd "$REPO" && git show f3c9bfb:build.gradle.kts) "$DST/build.gradle.kts" || true
    ;;
  build)
    # JDK_PATHS: 로컬에 설치하지 않은 toolchain(JDK 21 tarball 등)을 Gradle 에 알려 준다
    (cd "$DST" && ./gradlew --offline -q ${JDK_PATHS:+-Porg.gradle.java.installations.paths=$JDK_PATHS} -Porg.gradle.java.installations.auto-download=false bootJar)
    ls -l "$DST/build/libs/"
    ;;
  extract)
    rm -rf "$DST/run"; mkdir -p "$DST/run"
    cp -p "$DST/build/libs/blog-0.0.1-SNAPSHOT.jar" "$DST/run/"
    (cd "$DST/run" && "$J25" -Djarmode=tools -jar blog-0.0.1-SNAPSHOT.jar extract --destination application > /dev/null)
    shasum -a 256 "$DST/run/blog-0.0.1-SNAPSHOT.jar"; ls "$DST/run/application/lib" | wc -l
    ;;
esac
