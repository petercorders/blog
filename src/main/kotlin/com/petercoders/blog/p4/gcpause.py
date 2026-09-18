#!/usr/bin/env python3
"""-Xlog:gc*:file=gc.log:uptime(또는 time,uptime,level,tags) 로그에서 버킷별 pause 합계를 뽑아낸다. 사용법: python3 gcpause.py gc.log [버킷초]"""
import re, sys, collections
path, bucket = sys.argv[1], float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
pat = re.compile(r'\[(\d+\.\d+)s\].*?Pause[^\n]*?(\d+\.\d+)ms\s*$')   # uptime 괄호를 찾는다 (앞에 time 괄호가 있어도 상관없다)
tot = collections.OrderedDict(); cnt = collections.Counter()
for line in open(path, encoding='utf-8', errors='replace'):
    m = pat.search(line.rstrip())
    if m:
        b = int(float(m.group(1)) // bucket) * bucket
        tot[b] = tot.get(b, 0.0) + float(m.group(2)); cnt[b] += 1
for b in sorted(tot):
    print(f"{b:6.0f}s {tot[b]:8.1f}ms  ({cnt[b]:4d} pauses)  {tot[b]/(bucket*1000)*100:5.1f}%")
