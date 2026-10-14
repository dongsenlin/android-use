"""电脑端 MCP 服务的离线测试：用一个假手机（本机 HTTP 服务）代替真实的 Android Use App。
运行：python3 -m unittest discover -s tests -v
"""
import base64
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
import android_use as au  # noqa: E402

TOKEN = "testtoken234567abcdefghij"
JPEG = base64.b64encode(b"\xff\xd8\xff\xe0fakejpeg\xff\xd9").decode()


class FakePhone(http.server.BaseHTTPRequestHandler):
    calls = []
    screen_on = True
    locked = False

    def log_message(self, *a):
        pass

    def reply(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/v1/ping":
            return self.reply(200, {"ok": True, "app": "android-use", "version": "0.1.0", "auth": "bearer"})
        self.reply(404, {"ok": False})

    def do_POST(self):
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            return self.reply(401, {"ok": False, "error": {"code": "unauthorized", "message": "bad token"}})
        req = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        m, p = req["method"], req.get("params", {})
        FakePhone.calls.append((m, p))
        node = {"i": 0, "cls": "Button", "text": "发送", "rect": [900, 2200, 120, 80], "flags": "click"}
        results = {
            "status": {"manufacturer": "Fake", "model": "Phone", "android": "15", "sdk": 35,
                       "screen": {"width": 1080, "height": 2400}, "interactive": FakePhone.screen_on,
                       "locked": FakePhone.locked, "foreground": "com.tencent.mm", "keyboard_visible": False,
                       "capabilities": {"screenshot": True}},
            "tree": {"foreground": "com.tencent.mm", "screen": [1080, 2400], "nodes": [node], "truncated": False},
            "screenshot": {"mime": "image/jpeg", "data": JPEG, "width": 576, "height": 1280,
                           "screen_width": 1080, "screen_height": 2400, "foreground": "com.tencent.mm"},
            "find": {"count": 1 if p.get("selector", {}).get("label") == "发送" else 0,
                     "matches": [dict(node, tap=[960, 2240])] if p.get("selector", {}).get("label") == "发送" else []},
            "tap": {"point": [p.get("x", 960), p.get("y", 2240)]},
            "swipe": {"from": [p["x1"], p["y1"]], "to": [p["x2"], p["y2"]]} if m == "swipe" else {},
            "key": {"key": p.get("name")},
            "type": {"method": "set_text", "characters": len(p.get("text", "")), "readback": p.get("text"), "matches": True},
            "launch": {"package": p.get("package") or "com.tencent.mm"},
            "apps": {"count": 1, "apps": [{"label": "微信", "package": "com.tencent.mm"}]},
            "open_url": {"url": p.get("url")},
        }
        if m == "long_press" and p.get("x") == 13:
            return self.reply(200, {"ok": False, "error": {"code": "gesture_cancelled", "message": "cancelled"}})
        if m not in results and m != "long_press":
            return self.reply(200, {"ok": False, "error": {"code": "unknown_method", "message": m}})
        self.reply(200, {"ok": True, "result": results.get(m, {"point": [p.get("x"), p.get("y")]})})


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = free_port()
        cls.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", cls.port), FakePhone)
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["ANDROID_USE_STATE_DIR"] = self.tmp.name
        os.environ.pop("ANDROID_USE_URL", None)
        os.environ.pop("ANDROID_USE_TOKEN", None)
        FakePhone.calls.clear()
        FakePhone.screen_on, FakePhone.locked = True, False
        au.SETTLE_SECONDS = 0
        self.rt = au.Runtime()

    def tearDown(self):
        self.tmp.cleanup()

    def pair(self):
        return self.rt.call("android_setup", {"action": "pair", "pairing": f"androiduse://127.0.0.1:{self.port}/?token={TOKEN}"})

    def test_pairing_string_and_private_config(self):
        res, _ = self.pair()
        self.assertTrue(res["paired"])
        cfg = Path(self.tmp.name) / "config.json"
        self.assertEqual(oct(cfg.stat().st_mode & 0o777), "0o600")
        self.assertEqual(json.loads(cfg.read_text())["token"], TOKEN)
        status, _ = self.rt.call("android_setup", {"action": "status"})
        self.assertTrue(status["reachable"])
        self.assertNotIn(TOKEN, json.dumps(status))

    def test_pairing_rejects_garbage(self):
        with self.assertRaises(au.AUError):
            au.parse_pairing("wechat://nope")
        with self.assertRaises(au.AUError):
            au.parse_pairing("androiduse://<手机IP>:8787/?token=x")

    def test_wrong_token_is_reported_as_unauthorized(self):
        res = au.tool_result(self.rt, {"name": "android_setup", "arguments": {
            "action": "pair", "url": f"127.0.0.1:{self.port}", "token": "wrong"}})
        self.assertTrue(res["isError"])
        self.assertEqual(json.loads(res["content"][0]["text"])["error"]["code"], "unauthorized")

    def test_not_paired(self):
        res = au.tool_result(self.rt, {"name": "android_ready", "arguments": {}})
        self.assertEqual(json.loads(res["content"][0]["text"])["error"]["code"], "not_paired")

    def test_refused_connection_has_hint(self):
        port = free_port()
        res = au.tool_result(self.rt, {"name": "android_setup", "arguments": {
            "action": "pair", "url": f"127.0.0.1:{port}", "token": TOKEN}})
        err = json.loads(res["content"][0]["text"])["error"]
        self.assertEqual(err["code"], "phone_refused")
        self.assertIn("远程控制", err["hint"])

    def test_ready_returns_image_block_and_scale(self):
        self.pair()
        res = au.tool_result(self.rt, {"name": "android_ready", "arguments": {}})
        self.assertFalse(res["isError"])
        self.assertEqual([b["type"] for b in res["content"]], ["text", "image"])
        payload = json.loads(res["content"][0]["text"])
        self.assertTrue(payload["ready"])
        self.assertEqual(payload["observation"]["image"]["pixel_to_screen"], [1.875, 1.875])
        self.assertTrue(Path(payload["observation"]["image"]["path"]).is_file())

    def test_ready_needs_user_when_locked(self):
        self.pair()
        FakePhone.locked = True
        payload, images = self.rt.call("android_ready", {})
        self.assertFalse(payload["ready"])
        self.assertEqual(payload["state"], "needs_user")
        self.assertEqual(images, [])

    def test_swipe_direction_is_finger_movement(self):
        self.pair()
        self.rt.call("android_swipe", {"direction": "up"})
        _, p = FakePhone.calls[-1]
        self.assertGreater(p["y1"], p["y2"])
        self.rt.call("android_swipe", {"from": [10, 20], "to": [30, 40]})
        self.assertEqual(FakePhone.calls[-1][1]["x2"], 30)

    def test_tap_requires_point_or_selector(self):
        self.pair()
        with self.assertRaises(au.AUError):
            self.rt.call("android_tap", {"x": 5})
        res, images = self.rt.call("android_tap", {"selector": {"label": "发送"}, "observe": "screenshot"})
        self.assertIn("observation", res)
        self.assertEqual(len(images), 1)

    def test_batch_stops_at_failing_step_with_screenshot(self):
        self.pair()
        res, images = self.rt.call("android_batch", {"steps": [
            {"tool": "launch_app", "args": {"name": "微信"}},
            {"tool": "long_press", "args": {"x": 13, "y": 13}},
            {"tool": "press_key", "args": {"name": "back"}}]})
        self.assertEqual(res["stopped_at"], 1)
        self.assertEqual(res["error"]["code"], "gesture_cancelled")
        self.assertEqual(len(images), 1)
        self.assertNotIn(("key", {"name": "back"}), FakePhone.calls)

    def test_wait_and_scroll_find(self):
        self.pair()
        res, _ = self.rt.call("android_wait", {"selector": {"label": "发送"}, "timeout": 1})
        self.assertTrue(res["satisfied"])
        res, images = self.rt.call("android_scroll_find", {"selector": {"label": "不存在"}, "max_swipes": 3})
        self.assertFalse(res["found"])
        self.assertEqual(res["stop_reason"], "no_progress")  # 假手机的树永远不变
        self.assertEqual(len(images), 1)

    def test_curl_transport_fallback_path(self):
        # macOS 不给 Python 本地网络权限时会改走系统 curl；这里强制走 curl 跑一遍完整流程
        au._use_curl = True
        try:
            self.pair()
            payload, images = self.rt.call("android_ready", {})
            self.assertTrue(payload["ready"])
            self.assertEqual(len(images), 1)
            bad = au.Phone(f"http://127.0.0.1:{self.port}", "wrong")
            with self.assertRaises(au.AUError) as cm:
                bad.rpc("status")
            self.assertEqual(cm.exception.code, "unauthorized")
            with self.assertRaises(au.AUError) as cm:
                au.Phone(f"http://127.0.0.1:{free_port()}", TOKEN).ping()
            self.assertIn(cm.exception.code, ("phone_refused", "phone_unreachable"))
        finally:
            au._use_curl = False

    def test_mcp_stdio_roundtrip(self):
        env = dict(os.environ, ANDROID_USE_URL=f"http://127.0.0.1:{self.port}", ANDROID_USE_TOKEN=TOKEN)
        p = subprocess.Popen([sys.executable, str(ROOT / "server/android_use.py")], stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, text=True, env=env)
        msgs = [{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "android_apps", "arguments": {"query": "微信"}}},
                {"jsonrpc": "2.0", "id": 4, "method": "nope"}]
        out, _ = p.communicate("\n".join(json.dumps(m) for m in msgs) + "\n", timeout=20)
        replies = [json.loads(line) for line in out.splitlines()]
        self.assertEqual([r["id"] for r in replies], [1, 2, 3, 4])
        self.assertEqual(replies[0]["result"]["serverInfo"]["name"], "android-use")
        names = [t["name"] for t in replies[1]["result"]["tools"]]
        self.assertIn("android_tap", names)
        for t in replies[1]["result"]["tools"]:
            self.assertFalse(t["inputSchema"]["additionalProperties"])
        self.assertIn("微信", replies[2]["result"]["content"][0]["text"])
        self.assertEqual(replies[3]["error"]["code"], -32601)


if __name__ == "__main__":
    unittest.main()
