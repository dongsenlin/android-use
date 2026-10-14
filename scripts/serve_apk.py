#!/usr/bin/env python3
"""在局域网临时分享 dist/ 里的 APK：手机浏览器打开打印出的地址即可下载安装。Ctrl+C 结束。"""
import functools
import http.server
from pathlib import Path
import socket
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8790
dist = Path(__file__).resolve().parents[1] / "dist"
apks = sorted(dist.glob("android-use-*.apk"))
if not apks:
    sys.exit("dist/ 里没有 APK，先运行 sh app/build.sh")
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
    try:
        s.connect(("192.168.0.1", 9))  # 不会真的发包，只为拿到本机局域网地址
        ip = s.getsockname()[0]
    except OSError:
        ip = "<电脑局域网IP>"
print(f"手机和电脑连同一个 Wi‑Fi，用手机浏览器打开：\n  http://{ip}:{PORT}/{apks[-1].name}\n下载完点开安装（需允许安装未知来源应用）。Ctrl+C 结束分享。")
handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(dist))
http.server.ThreadingHTTPServer(("0.0.0.0", PORT), handler).serve_forever()
