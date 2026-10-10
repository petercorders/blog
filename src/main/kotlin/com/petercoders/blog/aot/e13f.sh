#!/usr/bin/env bash
# e13-f — E1(펼친 배치 + onRefresh 캐시, JDK 25.0.4.1)의 class+load 중 캐시 밖 줄 198개를 전부 분류한다.
#   새로 실행하지 않는다. e13-b 가 남긴 원본 class+load(runs/work-r2/classload-e13-E1.log)와
#   e21 의 JDK 25 training 출력(runs/work-r2/dirA/train-e21-25.out, -Xlog:aot=info)만 읽는다.
#   두 원본은 runs/work-r2/ 아래라 .gitignore 대상이므로, 분류 결과와 198줄 전문을 이 로그에 옮겨 둔다.
#
# 분류 규칙(위에서부터 먼저 맞는 것)
#   [A] training 이 Skipping 사유를 남긴 클래스와 그 nest 의 lambda·TypeSwitch
#       (Log4J2LoggingSystem: Failed verification, ClassFile* 8: Unsupported location(multi-release),
#        jdk.internal.event.*: JFR event class)
#   [B] source: __dynamic_proxy__
#   [C] source: __JVM_LookupDefineClass__ (LambdaForm$MH 등 JVM 이 실행 중 만드는 method handle 클래스)
#   [D] 런타임 load 순서에서 DefaultLifecycleProcessor 의 첫 lambda 이후에 처음 load 된 나머지 줄
#       onRefresh 는 DefaultLifecycleProcessor.onRefresh() 가 startBeans() 전에 Runtime.halt(0) 을 부르는 지점에서
#       끝난다(spring-framework v7.0.9 DefaultLifecycleProcessor.java 300~302행). 그 뒤의 Lifecycle 시작,
#       Started 줄(SpringApplication.java v4.1.1 1795행 ManagementFactory.getRuntimeMXBean().getUptime()),
#       runner 호출, 종료 처리는 training 에 없다.
#   [E] 나머지(refresh 중에 load 됐는데 캐시에 없는 lambda)
source "$(dirname "$0")/r2-env.sh"
CL=$R2/classload-e13-E1.log
TR=$R2/dirA/train-e21-25.out
echo "# experiment: e13-f — E1 class+load 중 캐시 밖 198줄의 내역(새 실행 없음, 원본 로그 재집계)"
echo "# date: $(date '+%Y-%m-%d %H:%M:%S %z')"
echo "# 입력 1: runs/work-r2/classload-e13-E1.log (e13-b 원본) sha256 $(shasum -a 256 "$CL" | cut -c1-16)…"
echo "# 입력 2: runs/work-r2/dirA/train-e21-25.out (e21 JDK 25 training, -Xlog:aot=info) sha256 $(shasum -a 256 "$TR" | cut -c1-16)…"
echo "## JDK 25 training 의 Skipping(Proxy 제외)"
grep 'Skipping' "$TR" | grep -v 'Proxy[0-9]' | sed -E 's/^\[[0-9.]+s\]//; s/^/    /'
echo "    Proxy 의 Skipping(Unsupported location): $(grep 'Skipping' "$TR" | grep -c 'Proxy[0-9]')"
python3 - "$CL" <<'EOF'
import sys, collections
lines = open(sys.argv[1]).read().splitlines()
total = sum(1 for l in lines if 'source:' in l)
shared = sum(1 for l in lines if 'source: shared objects file' in l)
first_dlp = next(i for i, l in enumerate(lines, 1)
                 if l.startswith('org.springframework.context.support.DefaultLifecycleProcessor$$Lambda') and 'shared objects file' not in l)
cat = collections.OrderedDict((k, []) for k in 'ABCDE')
def short(src):
    return src.rsplit('/', 1)[-1] if src.startswith('file:') else src
for i, l in enumerate(lines, 1):
    if 'source:' not in l or 'shared objects file' in l:
        continue
    name, src = l.split(' source: ', 1)
    excluded = ('Log4J2LoggingSystem' in name or 'classreading.ClassFile' in name
                or 'classreading.ClassFile' in src or name.startswith('jdk.internal.event.'))
    if excluded: c = 'A'
    elif src == '__dynamic_proxy__': c = 'B'
    elif src == '__JVM_LookupDefineClass__': c = 'C'
    elif i >= first_dlp: c = 'D'
    else: c = 'E'
    cat[c].append((i, name, short(src)))
label = {
    'A': 'training 이 사유를 남기고 뺀 클래스와 그 nest 의 lambda·TypeSwitch',
    'B': 'dynamic Proxy(source __dynamic_proxy__)',
    'C': 'method handle 구현 클래스(source __JVM_LookupDefineClass__)',
    'D': 'onRefresh 종료 지점 뒤에 처음 load 된 클래스(Lifecycle 시작·Started 줄·runner·종료)',
    'E': 'refresh 중 load 됐는데 캐시에 없는 lambda(원인 미특정)',
}
print(f"E1 class+load total={total} shared={shared} nonshared={total - shared}")
print(f"D 경계: 런타임 load 순서 {first_dlp}번째 줄(DefaultLifecycleProcessor 의 첫 lambda)")
for k, v in cat.items():
    print(f"[{k}] {len(v)} — {label[k]}")
a = cat['A']
cls = [x for x in a if '$$' not in x[1]]
print(f"    A 내역: 클래스 {len(cls)}(Log4J2LoggingSystem {sum('Log4J2' in x[1] for x in cls)}, "
      f"ClassFile* {sum('ClassFile' in x[1] for x in cls)}, jdk.internal.event {sum(x[1].startswith('jdk.internal.event') for x in cls)}) "
      f"+ nest 의 lambda·TypeSwitch {len(a) - len(cls)}")
d = cat['D']
kinds = collections.Counter('jrt' if x[2].startswith('jrt:') else 'file' if x[2].endswith('.jar') else 'lambda' for x in d)
print(f"    D 내역: JDK 모듈(jrt:) {kinds['jrt']}, 앱 클래스패스 jar {kinds['file']}, lambda {kinds['lambda']}")
print(f"합계 {' + '.join(str(len(v)) for v in cat.values())} = {sum(len(v) for v in cat.values())}")
for k, v in cat.items():
    print(f"## [{k}] 전문")
    for i, n, s in v:
        print(f"    {i:5d} {n}  <- {s}")
EOF
