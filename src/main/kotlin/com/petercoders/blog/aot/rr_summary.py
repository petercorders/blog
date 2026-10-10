#!/usr/bin/env python3
"""라운드로빈 로그 요약(e19·e20). run_once 줄('<조건> r=<n> wall_ms=… exit=… Started … in X seconds (process running for Y)')을 읽어
조건별 중앙값·최솟값과, 같은 라운드 기준 조건 대비 비율의 중앙값, 라운드별 승패를 낸다.
사용: rr_summary.py <log> <기준 조건> <조건...>"""
import re, sys, statistics as st

log, base, conds = sys.argv[1], sys.argv[2], sys.argv[3:]
rows = {}
for line in open(log):
    m = re.match(r'^(\S+) r=(\d+) wall_ms=(\d+) exit=(\d+) Started \w+ in ([0-9.]+) seconds \(process running for ([0-9.]+)\)', line)
    if m:
        rows.setdefault(m.group(1), {})[int(m.group(2))] = (int(m.group(3)), float(m.group(5)), float(m.group(6)))
print(f"## 요약 — ratio = 같은 라운드 {base} 대비 비율의 중앙값")
b = rows.get(base, {})
for c in conds:
    d = rows.get(c, {})
    if not d:
        print(f"{c} n=0"); continue
    w = [v[0] for v in d.values()]; s = [v[1] for v in d.values()]; p = [v[2] for v in d.values()]
    rs = [d[r][1] / b[r][1] for r in d if r in b]
    rw = [d[r][0] / b[r][0] for r in d if r in b]
    print(f"{c} n={len(d)} median_wall_ms={st.median(w):.0f} median_started_s={st.median(s):.3f} median_process_s={st.median(p):.3f} "
          f"min_started_s={min(s):.3f} started/{base}={st.median(rs):.3f} wall/{base}={st.median(rw):.3f} "
          f"wins_vs_{base}(started)={sum(1 for r in d if r in b and d[r][1] < b[r][1])}/{len(rs)}")
