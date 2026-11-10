"""Finds the port Wear OS wireless debugging is listening on, and connects.

Wear OS picks a random port each time adbd restarts, which happens whenever
the watch's Wi-Fi comes back, and the watch drops Wi-Fi within seconds of
going idle, answering nothing at all while asleep. So the script keeps
trying until the wait runs out: the port last seen, the ones adb's own
mDNS discovery reports, then a scan of the 30000-49999 range wireless
debugging draws from, about forty seconds a pass. Prints the working
adb serial (host:port, IPv6 hosts in brackets), or nothing when the watch
never answered within the wait.

Several hosts can be given, comma-separated, and each is tried in turn:
the watch's IPv6 address reaches it even when an IPv4 route (a Tailscale
subnet route, say) swallows the LAN.

    python scripts/find-watch-port.py <watch ip>[,<ipv6>] [seconds to wait, default 60]
"""
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

if len(sys.argv) < 2:
    sys.exit("usage: find-watch-port.py <watch ip>[,<ipv6>] [seconds to wait]")
hosts = sys.argv[1].split(",")
wait = int(sys.argv[2]) if len(sys.argv) > 2 else 60
ports = [5555] + list(range(30000, 50000))
cache = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".watch-port")

adb = shutil.which("adb") or os.path.expandvars(
    r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)

OPEN, CLOSED, SILENT = "open", "closed", "silent"


def serial(host, port):
    return f"[{host}]:{port}" if ":" in host else f"{host}:{port}"


def probe(host, port, timeout=1.0):
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    s = socket.socket(family, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        return OPEN
    except socket.timeout:
        return SILENT
    except OSError:
        return CLOSED
    finally:
        s.close()


def run(*args):
    return subprocess.run([adb, *args], capture_output=True, text=True, timeout=20)


def confirm(host, port):
    """True when adb sees a device at this port; the first answer after a
    connect is often "offline" for a second or two."""
    target = serial(host, port)
    try:
        run("disconnect", target)
        run("connect", target)
        for _ in range(5):
            if run("-s", target, "get-state").stdout.strip() == "device":
                return True
            time.sleep(1)
    except subprocess.TimeoutExpired:
        pass
    run("disconnect", target)
    return False


def remembered():
    try:
        with open(cache) as f:
            return [int(f.read().strip())]
    except (OSError, ValueError):
        return []


def advertised():
    try:
        out = run("mdns", "services").stdout
    except subprocess.TimeoutExpired:
        return []
    return [int(p) for h, p in re.findall(r"(\d+\.\d+\.\d+\.\d+):(\d+)", out) if h in hosts]


def scanned(host):
    with ThreadPoolExecutor(max_workers=512) as pool:
        return [p for p, state in zip(ports, pool.map(lambda p: probe(host, p), ports)) if state == OPEN]


def finish(host, port):
    with open(cache, "w") as f:
        f.write(str(port))
    print(serial(host, port))
    sys.exit(0)


deadline = time.time() + wait
while True:
    for host in hosts:
        for port in remembered() + advertised():
            if probe(host, port) == OPEN and confirm(host, port):
                finish(host, port)
    for host in hosts:
        for port in scanned(host):
            if confirm(host, port):
                finish(host, port)
    if time.time() >= deadline:
        break
    time.sleep(3)
