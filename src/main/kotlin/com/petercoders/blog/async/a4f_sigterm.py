#!/usr/bin/env python3
"""SPRING-INTERNALS 5편 [A4f] — close() 를 직접 부르는 A4 계열과 달리 실제 SIGTERM 경로로 종료를 잰다.

usage: a4f_sigterm.py <java 실행 파일> <classpath> <platform|vt>
  classpath 는 ./gradlew --offline -q asyncLabClasspath 출력 한 줄.
순서: java 로 AsyncLabKt A4fServe 를 띄운다(server.port=0, server.shutdown=graceful,
      spring.lifecycle.timeout-per-shutdown-phase=3s) → 'A4F_PORT=' 줄을 기다린다 →
      GET /slow 를 백그라운드로 보낸다(/slow 는 8초 @Async 를 넘기고 자신은 5초 걸린다) →
      1초 뒤 kill -TERM → 프로세스가 끝날 때까지 기다린다.
출력: JVM 출력 줄마다 스크립트 시작 기준 ms, SIGTERM→종료 ms, /slow 응답, 마커 파일 유무.
"""
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

java, cp, mode = sys.argv[1:4]
marker = os.path.join(tempfile.gettempdir(), f"a4f-marker-{mode}-{os.getpid()}.txt")
if os.path.exists(marker):
    os.remove(marker)
args = [java, f"-Da4f.marker={marker}", "-cp", cp, "com.petercoders.blog.async.AsyncLabKt", "A4fServe"]
if mode == "vt":
    args.append("vt")

t0 = time.monotonic()
def ms() -> int:
    return int((time.monotonic() - t0) * 1000)

def out(line: str) -> None:
    print(f"[{ms():6d}ms] {line}", flush=True)

port = None
port_ready = threading.Event()
proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)

def reader() -> None:
    global port
    for raw in proc.stdout:
        line = raw.rstrip("\n")
        out("jvm| " + line)
        if line.startswith("A4F_PORT="):
            port = int(line.split("=", 1)[1])
            port_ready.set()

rt = threading.Thread(target=reader, daemon=True)
rt.start()
if not port_ready.wait(60):
    out("script| A4F_PORT 를 60초 안에 받지 못했다")
    proc.kill()
    sys.exit(1)

slow_result = {}
def call_slow() -> None:
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/slow", timeout=20) as r:
            slow_result["v"] = f"HTTP {r.status} body={r.read().decode()!r}"
    except Exception as e:  # noqa: BLE001 — 결과를 그대로 남긴다
        slow_result["v"] = f"{type(e).__name__}: {e}"
    out("script| /slow 응답: " + slow_result["v"])

out(f"script| GET /slow 시작 (port={port})")
ct = threading.Thread(target=call_slow, daemon=True)
ct.start()
time.sleep(1.0)
t_term = ms()
out(f"script| kill -TERM {proc.pid}")
os.kill(proc.pid, signal.SIGTERM)
code = proc.wait(timeout=60)
t_exit = ms()
rt.join(timeout=2)
ct.join(timeout=5)
out(f"script| 프로세스 종료 exit={code}")
out(f"script| SIGTERM → 프로세스 종료 = {t_exit - t_term}ms")
out(f"script| 마커 파일({marker}) = {'있음' if os.path.exists(marker) else '없음'}")
