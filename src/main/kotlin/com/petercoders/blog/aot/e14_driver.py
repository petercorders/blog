#!/usr/bin/env python3
"""e14 드라이버 — web starter 를 붙인 변형 앱을 띄우고 첫 요청을 잰다.

server.port=0 으로 띄우고, 로그의 'Tomcat started on port N' 줄에서 포트를 읽는다.

모드
  measure : 프로세스 시작 → 첫 200 응답까지 시간, 첫 요청 지연, 첫 100 요청 p50/p99
  diag    : -Xlog:class+load 파일과 -XX:+PrintCompilation 을 켠 채로,
            첫 요청 동안 새로 load 된 클래스 수(그 중 shared 가 아닌 것), 100 요청 뒤까지의 컴파일 수
  train   : 요청 N 회를 넣은 뒤 training 을 끝낸다(--end jcmd | mx | term)
요청 하나 = GET /api/items/{i}, GET /api/orders?n=3, POST /api/echo 를 돌아가며.
"""
import argparse, http.client, json, os, re, signal, statistics, subprocess, sys, threading, time

APP = ['-Dserver.port=0', '-jar', 'application/blog-0.0.1-SNAPSHOT.jar']
PORT_RE = re.compile(r'Tomcat started on port (\d+)')
STARTED_RE = re.compile(r'Started \w+ in ([0-9.]+) seconds \(process running for ([0-9.]+)\)')
COMPILE_RE = re.compile(r'^\s*\d+\s+\d+\s+[%sbn! ]*[0-4]\s+\S+::\S+ (@ \d+ )?\((\d+ bytes|native)\)(?!.*made not entrant)')


class App:
    def __init__(self, java, cwd, jvm_opts, log_lines=None):
        self.lines = []
        self.port = None
        self.started = None
        self.port_evt = threading.Event()
        self.t0 = time.perf_counter()
        self.p = subprocess.Popen([java] + jvm_opts + APP, cwd=cwd, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.th = threading.Thread(target=self._read, daemon=True)
        self.th.start()

    def _read(self):
        for line in self.p.stdout:
            self.lines.append(line.rstrip('\n'))
            if self.port is None:
                m = PORT_RE.search(line)
                if m:
                    self.port = int(m.group(1))
                    self.port_evt.set()
            if self.started is None:
                m = STARTED_RE.search(line)
                if m:
                    self.started = (float(m.group(1)), float(m.group(2)))
        self.port_evt.set()

    def wait_port(self, timeout=120):
        self.port_evt.wait(timeout)
        if self.port is None:
            raise RuntimeError('no port; last lines:\n' + '\n'.join(self.lines[-15:]))

    def stop(self):
        if self.p.poll() is None:
            self.p.send_signal(signal.SIGTERM)
        try:
            self.p.wait(60)
        except subprocess.TimeoutExpired:
            self.p.kill(); self.p.wait()
        self.th.join(10)
        return self.p.returncode


def request(conn, i):
    k = i % 3
    if k == 0:
        conn.request('GET', f'/api/items/{i}')
    elif k == 1:
        conn.request('GET', '/api/orders?n=3')
    else:
        body = json.dumps({'id': i, 'name': f'n{i}', 'tags': ['a', 'b'], 'nested': {'x': 1.5}})
        conn.request('POST', '/api/echo', body=body, headers={'Content-Type': 'application/json'})
    r = conn.getresponse()
    data = r.read()
    if r.status != 200:
        raise RuntimeError(f'status {r.status}: {data[:200]!r}')
    return r.status


def timed_requests(port, n, start=0):
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=30)
    lat = []
    for i in range(start, start + n):
        t = time.perf_counter()
        request(conn, i)
        lat.append((time.perf_counter() - t) * 1000)
    conn.close()
    return lat


def pct(xs, q):
    s = sorted(xs)
    return s[min(len(s) - 1, max(0, int(round(q / 100 * len(s) + 0.5)) - 1))]


def count_lines(path):
    try:
        with open(path) as f:
            ls = f.readlines()
    except FileNotFoundError:
        return 0, 0
    return len(ls), sum(1 for l in ls if 'source: shared objects file' not in l)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--mode', required=True, choices=['measure', 'diag', 'train'])
    ap.add_argument('--java', required=True)
    ap.add_argument('--cwd', required=True)
    ap.add_argument('--label', required=True)
    ap.add_argument('--requests', type=int, default=100)
    ap.add_argument('--end', default='term', choices=['jcmd', 'mx', 'term'])
    ap.add_argument('--classload', default=None)
    ap.add_argument('jvm', nargs=argparse.REMAINDER)
    a = ap.parse_args()
    jvm = [x for x in a.jvm if x != '--']

    if a.mode == 'measure':
        app = App(a.java, a.cwd, jvm)
        app.wait_port()
        t_port = (time.perf_counter() - app.t0) * 1000
        lat = timed_requests(app.port, a.requests)
        t_first200 = t_port + lat[0]  # 포트 줄을 본 직후 첫 요청을 보냈다
        rc = app.stop()
        st = app.started or (float('nan'), float('nan'))
        print(f'{a.label} port_seen_ms={t_port:.0f} first200_ms={t_first200:.0f} first_req_ms={lat[0]:.1f} '
              f'p50_ms={statistics.median(lat):.2f} p99_ms={pct(lat, 99):.2f} n={len(lat)} '
              f'started_s={st[0]} process_s={st[1]} exit={rc}')
    elif a.mode == 'diag':
        cl = a.classload
        app = App(a.java, a.cwd, jvm + [f'-Xlog:class+load:file={cl}:none', '-XX:+PrintCompilation'])
        app.wait_port()
        time.sleep(0.5)
        n0, ns0 = count_lines(cl)
        c0 = sum(1 for l in list(app.lines) if COMPILE_RE.match(l))
        lat = timed_requests(app.port, 1)
        time.sleep(0.5)
        n1, ns1 = count_lines(cl)
        rest = timed_requests(app.port, a.requests - 1, start=1)
        time.sleep(0.3)
        n2, ns2 = count_lines(cl)
        lines = list(app.lines)
        comp = [l for l in lines if COMPILE_RE.match(l)]
        tier4 = sum(1 for l in comp if re.match(r'^\s*\d+\s+\d+\s+[%sbn! ]*4\s', l))
        rc = app.stop()
        print(f'{a.label} classload_before_first={n0} (nonshared {ns0}) during_first_req={n1 - n0} (nonshared {ns1 - ns0}) '
              f'during_next_{a.requests - 1}={n2 - n1} (nonshared {ns2 - ns1}) total={n2} (nonshared {ns2}) '
              f'compiles_before_first={c0} compiles_after_{a.requests}req={len(comp)} tier4={tier4} first_req_ms={lat[0]:.1f} exit={rc}')
    else:  # train
        t0 = time.perf_counter()
        app = App(a.java, a.cwd, jvm)
        app.wait_port()
        lat = timed_requests(app.port, a.requests)
        t_load = (time.perf_counter() - t0) * 1000
        out = ''
        if a.end == 'jcmd':
            jcmd = os.path.join(os.path.dirname(a.java), 'jcmd')
            r = subprocess.run([jcmd, str(app.p.pid), 'AOT.end_recording'], capture_output=True, text=True)
            out = (r.stdout + r.stderr).strip().replace('\n', ' | ')
        elif a.end == 'mx':
            conn = http.client.HTTPConnection('127.0.0.1', app.port, timeout=120)
            conn.request('POST', '/api/aot/end')
            r = conn.getresponse(); out = r.read().decode()
            conn.close()
        t_end = (time.perf_counter() - t0) * 1000
        time.sleep(0.5)
        rc = app.stop()
        t_exit = (time.perf_counter() - t0) * 1000
        print(f'{a.label} requests={a.requests} load_done_ms={t_load:.0f} end_called_ms={t_end:.0f} '
              f'process_exit_ms={t_exit:.0f} exit={rc} end={a.end} end_output=[{out[:300]}]')
        for l in app.lines:
            if re.search(r'\]\[aot|AOTCache creation|Launching child|Picked up|end_recording|Exception|Error', l):
                print('    ' + l[:260])


if __name__ == '__main__':
    main()
