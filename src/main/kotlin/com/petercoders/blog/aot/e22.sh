#!/usr/bin/env bash
# e22 — training run 이 이미지 빌드 환경에서 깨지는 지점(Spring #36102 조합)과 training 전용 설정(JDK 25.0.4.1).
#   변형 앱 variants/e22-props: f3c9bfb + @ConfigurationProperties("app") data class AppProps(val url: String) (기본값 없음)
#   a  app.url 없이 한 줄 방식 training → exit, 캐시 생성 여부, 실패 메시지
#   b  training 에만 -Dapp.url=dummy → 운영은 다른 값으로 기동 → AOTMode=on exit, shared 수, 운영이 본 값
#   b2 training 에만 JAVA_TOOL_OPTIONS=-Dapp.url=… (Paketo TRAINING_RUN_JAVA_TOOL_OPTIONS 와 같은 방식) → 운영 게이트
#   c  (선택) data-jpa + 닿지 않는 DB — offline Gradle 캐시에 spring-boot-starter-data-jpa 4.1.1 이 없어 돌리지 않았다
# 사용: e22.sh prep|run
source "$(dirname "$0")/r2-env.sh"
W=$R2/props
case ${1:?prep|run} in
prep)
  rm -rf "$W"; mkdir -p "$W"
  cp -Rp /tmp/gradle-aot-e22-props/run/application "$W/"
  echo "# e22 변형 jar sha256 $(shasum -a 256 /tmp/gradle-aot-e22-props/run/$APPJAR | cut -c1-64) size $(stat -f %z /tmp/gradle-aot-e22-props/run/$APPJAR)"
  unzip -l "$W/application/$APPJAR" | grep 'props/' | awk '{print "#   " $4}'
  ;;
run)
  header e22 "Kotlin non-null @ConfigurationProperties 와 training run" "$J25"
  cd "$W"; rm -f *.aot *.aot.config
  echo "## (a) app.url 없이: java -XX:AOTCacheOutput=a.aot -Dspring.context.exit=onRefresh -jar application/$APPJAR"
  "$J25" -XX:AOTCacheOutput=a.aot -Dspring.context.exit=onRefresh -jar application/$APPJAR > a.out 2>&1; rc=$?
  echo "exit=$rc cache=$(stat -f %z a.aot 2>/dev/null || echo 없음) config=$(ls a.aot.config 2>/dev/null || echo 없음)"
  grep -nE 'non-null|Parameter specified|Failed to bind|APPLICATION FAILED|Description:|Reason:|Action:|BeanCreationException|NullPointerException|Launching child|AOTCache creation|\]\[aot\].*(error|Error)' a.out | grep -v 'cannot be archived' | head -12 | cut -c1-260 | sed 's/^/    /'
  echo "## (a'') 실패한 training 이 남긴 a.aot 를 운영(app.url 있음)에서 쓰면"
  "$J25" -Dapp.url=https://prod.example -XX:AOTCache=a.aot -Xlog:class+load:file=cl-a.log:none -jar application/$APPJAR > a-run.out 2>&1; echo "운영(auto) exit=$? $(count_sources cl-a.log) $(grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' a-run.out)"
  "$J25" -Dapp.url=https://prod.example -XX:AOTCache=a.aot -XX:AOTMode=on -jar application/$APPJAR > /dev/null 2>&1; echo "운영(on) exit=$?"
  echo "## (a') 같은 조건을 3단계 record 로(실패한 training 이 configuration 을 남기는가)"
  "$J25" -XX:AOTMode=record -XX:AOTConfiguration=a3.aotconf -Dspring.context.exit=onRefresh -jar application/$APPJAR > a3.out 2>&1; echo "record exit=$? aotconf=$(stat -f %z a3.aotconf 2>/dev/null || echo 없음)"
  echo "## (b) training 에만 -Dapp.url=http://training.invalid"
  "$J25" -Dapp.url=http://training.invalid -XX:AOTCacheOutput=b.aot -Dspring.context.exit=onRefresh -jar application/$APPJAR > b.out 2>&1; echo "train exit=$? cache=$(stat -f %z b.aot 2>/dev/null || echo 없음) $(grep -m1 -oE '\[e22\] app.url=.*' b.out)"
  "$J25" -Dapp.url=https://prod.example -XX:AOTCache=b.aot -Xlog:class+load:file=cl-b.log:none -jar application/$APPJAR > b-run.out 2>&1; echo "운영(auto) exit=$? $(count_sources cl-b.log) $(grep -oE 'Started [A-Za-z]+ in [0-9.]+ seconds' b-run.out)"
  grep -m1 -oE '\[e22\] app.url=.*' b-run.out | sed 's/^/    운영이 본 값: /'
  "$J25" -Dapp.url=https://prod.example -XX:AOTCache=b.aot -XX:AOTMode=on -jar application/$APPJAR > /dev/null 2>&1; echo "운영(on) exit=$?"
  APP_URL=https://prod.example "$J25" -XX:AOTCache=b.aot -XX:AOTMode=on -jar application/$APPJAR > b-env.out 2>&1; echo "운영(on, 환경변수 APP_URL) exit=$? $(grep -m1 -oE '\[e22\] app.url=.*' b-env.out)"
  echo "## (b2) training 에만 JAVA_TOOL_OPTIONS=-Dapp.url=http://training.invalid (자식 assembly 도 JAVA_TOOL_OPTIONS 를 쓴다)"
  JAVA_TOOL_OPTIONS=-Dapp.url=http://training.invalid "$J25" -XX:AOTCacheOutput=b2.aot -Dspring.context.exit=onRefresh -jar application/$APPJAR > b2.out 2>&1; echo "train exit=$? cache=$(stat -f %z b2.aot 2>/dev/null || echo 없음)"
  grep -E 'Picked up JAVA_TOOL_OPTIONS|AOTCache creation|\[e22\]' b2.out | cut -c1-240 | sed 's/^/    /'
  "$J25" -Dapp.url=https://prod.example -XX:AOTCache=b2.aot -XX:AOTMode=on -jar application/$APPJAR > b2-run.out 2>&1; echo "운영(on) exit=$? $(grep -m1 -oE '\[e22\] app.url=.*' b2-run.out)"
  echo "## (c) not_run — data-jpa 4.1.1 이 offline Gradle 캐시에 없다(~/.gradle/caches/modules-2/files-2.1/org.springframework.boot/spring-boot-starter-data-jpa 에 3.0.4 만 있다)"
  ;;
esac
