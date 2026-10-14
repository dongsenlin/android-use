#!/usr/bin/env python3
"""Android Use：让 Codex、Claude Code 等 Agent 通过 Wi‑Fi 操作安卓手机的 MCP 服务（stdio，只用标准库）。

手机上装 Android Use App 并开启无障碍服务与「允许 Agent 远程控制」，电脑这边配对一次即可。
设计参考 zhongerxin/iPhone-use（MIT）：先 READY 再操作、乐观执行、界面异常先看截图、关键结果才验收。
"""
import argparse
import base64
import errno
import json
import os
from pathlib import Path
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

VERSION = "0.1.2"
PROTOCOLS = ("2025-06-18", "2025-03-26", "2024-11-05")
DEFAULT_PORT = 8787
SETTLE_SECONDS = 0.6
FOCUS_WAIT_SECONDS = 2.0
LAUNCH_WAIT_SECONDS = 5.0
KEEP_ARTIFACTS = 60

INSTRUCTIONS = (
    "Android Use 通过 Wi‑Fi 操作用户自己的安卓手机。本对话第一次用手机先调 android_ready；"
    "没配对或连不上时按 android-use-setup 技能处理。坐标一律是手机屏幕像素，和界面树 rect 同一坐标系；"
    "截图像素乘以 image.pixel_to_screen 得到屏幕像素。界面树缺字或点不准时以截图为准。"
    "付款、下单、发送、删除等不可撤回的操作，执行前必须得到用户明确同意；密码、验证码、指纹交给用户本人。"
)


# ---------------------------------------------------------------------------- 错误与状态目录

class AUError(Exception):
    def __init__(self, code, message, hint=None, details=None):
        super().__init__(message)
        self.code, self.message, self.hint, self.details = code, message, hint, details

    def as_dict(self):
        d = {"code": self.code, "message": self.message}
        if self.hint:
            d["hint"] = self.hint
        if self.details:
            d["details"] = self.details
        return d


def state_dir():
    path = Path(os.environ.get("ANDROID_USE_STATE_DIR") or Path.home() / ".local/share/android-use")
    path.mkdir(parents=True, exist_ok=True)
    os.chmod(path, 0o700)
    return path


def write_private(path, data):
    tmp = path.with_name("." + path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def load_config():
    url, token = os.environ.get("ANDROID_USE_URL"), os.environ.get("ANDROID_USE_TOKEN")
    if url and token:
        return {"url": url.rstrip("/"), "token": token, "source": "env"}
    path = state_dir() / "config.json"
    try:
        cfg = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    return cfg if cfg.get("url") and cfg.get("token") else None


def parse_pairing(pairing=None, url=None, token=None):
    """接受 App 里复制的 androiduse://IP:端口/?token=… ，或分开给的 url + token。"""
    if pairing:
        p = urllib.parse.urlparse(pairing.strip())
        if p.scheme not in ("androiduse", "http"):
            raise AUError("invalid_argument", "配对信息应以 androiduse:// 开头，请从手机 App 的「复制配对信息」获取。")
        q = urllib.parse.parse_qs(p.query)
        token = token or (q.get("token") or [None])[0]
        host, port = p.hostname, p.port or DEFAULT_PORT
        if not host or host.startswith("<"):
            raise AUError("invalid_argument", "配对信息里没有手机 IP：手机需要先连上 Wi‑Fi。")
        url = f"http://{host}:{port}"
    if not url or not token:
        raise AUError("invalid_argument", "需要 pairing，或同时给出 url 与 token。")
    if not url.startswith("http://"):
        url = "http://" + url
    return url.rstrip("/"), token.strip()


# ---------------------------------------------------------------------------- 手机通道

NETWORK_HINT = ("确认手机和电脑在同一个 Wi‑Fi、手机 App 里「允许 Agent 远程控制」是开着的。"
                "macOS 上还要允许宿主程序访问本地网络：系统设置 → 隐私与安全性 → 本地网络，"
                "打开 Codex / Claude / 终端 / VS Code 对应的开关（第一次连接时系统也会弹窗询问）。"
                "用 USB 和 adb 的话也可以 `adb forward tcp:8787 tcp:8787` 后把地址配成 127.0.0.1:8787。")
REFUSED_HINT = ("在手机上打开 Android Use，确认无障碍服务已开启、「允许 Agent 远程控制」已打开。"
                "刚才还连得上、突然拒绝连接的，多半是有人点了手机顶部的紫色提示条（点一下就暂停远程控制）。")


CURL = "/usr/bin/curl"
# macOS 把 Python（框架版本是个 Python.app）当成独立 App 管"本地网络"权限，常常没有授权，
# 而系统自带的 curl 跟随宿主（终端 / Codex 等）的权限。Python 连局域网被拦时改用 curl。
_use_curl = os.environ.get("ANDROID_USE_TRANSPORT") == "curl"


def curl_request(req, timeout):
    import subprocess
    import tempfile
    lines = [f'url = "{req.full_url}"', f'request = "{req.get_method()}"', "silent", "show-error",
             f"max-time = {int(max(1, timeout))}", 'write-out = "\\n%{http_code}"']
    for k, v in req.header_items():
        lines.append(f'header = "{k}: {v}"')
    if req.data is not None:
        lines.append('data-binary = "@-"')
    # 配对码写进仅本人可读的临时配置文件，不出现在进程列表里
    with tempfile.NamedTemporaryFile("w", suffix=".curlrc", delete=False) as f:
        os.chmod(f.name, 0o600)
        f.write("\n".join(lines) + "\n")
        cfg = f.name
    try:
        p = subprocess.run([CURL, "-K", cfg], input=req.data or b"", capture_output=True, timeout=timeout + 5)
    finally:
        os.unlink(cfg)
    body, _, status = p.stdout.rpartition(b"\n")
    if p.returncode == 28:
        raise socket.timeout("timed out")
    if p.returncode != 0 or not status.isdigit() or status == b"000":
        err = OSError(p.stderr.decode(errors="replace").strip() or f"curl exit {p.returncode}")
        err.errno = errno.ECONNREFUSED if p.returncode == 7 and b"refused" in p.stderr.lower() else errno.EHOSTUNREACH
        raise err
    return int(status), body


class Phone:
    def __init__(self, url, token):
        self.url, self.token = url.rstrip("/"), token

    def _open(self, req, timeout):
        global _use_curl
        if not _use_curl:
            try:
                return self._open_urllib(req, timeout)
            except AUError as e:
                # 只有"路由不可达"像是被系统权限拦截；拒绝连接、超时等照常报错
                if e.code != "phone_unreachable" or "No route to host" not in e.message or not os.path.exists(CURL):
                    raise
                _use_curl = True
        try:
            return curl_request(req, timeout)
        except (OSError, socket.timeout) as e:
            if isinstance(e, socket.timeout):
                raise AUError("phone_timeout", f"手机 {self.url} 在 {timeout:.0f} 秒内没有响应。", NETWORK_HINT)
            if getattr(e, "errno", None) == errno.ECONNREFUSED:
                raise AUError("phone_refused", f"{self.url} 拒绝连接：App 的远程控制没开，或无障碍服务被系统关掉了。",
                              REFUSED_HINT)
            raise AUError("phone_unreachable", f"连不上手机 {self.url}：{e}", NETWORK_HINT)

    def _open_urllib(self, req, timeout):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except (urllib.error.URLError, OSError) as e:
            reason = getattr(e, "reason", e)
            if isinstance(reason, socket.timeout) or isinstance(e, socket.timeout) or "timed out" in str(reason):
                raise AUError("phone_timeout", f"手机 {self.url} 在 {timeout:.0f} 秒内没有响应。", NETWORK_HINT)
            code = getattr(reason, "errno", None)
            if code == errno.ECONNREFUSED:
                raise AUError("phone_refused", f"{self.url} 拒绝连接：App 的远程控制没开，或无障碍服务被系统关掉了。",
                              REFUSED_HINT)
            raise AUError("phone_unreachable", f"连不上手机 {self.url}：{reason}", NETWORK_HINT)

    def ping(self, timeout=5):
        status, body = self._open(urllib.request.Request(self.url + "/v1/ping"), timeout)
        try:
            data = json.loads(body)
        except ValueError:
            data = {}
        if status != 200 or data.get("app") != "android-use":
            raise AUError("not_android_use", f"{self.url} 上运行的不是 Android Use（HTTP {status}）。")
        return data

    def rpc(self, method, params=None, timeout=20):
        body = json.dumps({"method": method, "params": params or {}}, ensure_ascii=False).encode()
        req = urllib.request.Request(self.url + "/v1/rpc", data=body, method="POST", headers={
            "Content-Type": "application/json", "Authorization": "Bearer " + self.token})
        status, raw = self._open(req, timeout)
        try:
            data = json.loads(raw)
        except ValueError:
            raise AUError("invalid_response", f"手机返回了无法解析的内容（HTTP {status}）。")
        if status == 401:
            raise AUError("unauthorized", "配对码不对：手机上可能点过「换一个配对码」。",
                          "在手机 App 里复制新的配对信息，再调用 android_setup(action=\"pair\")。")
        if not data.get("ok"):
            err = data.get("error") or {}
            raise AUError(err.get("code", "phone_error"), err.get("message", f"HTTP {status}"), details=err.get("details"))
        return data.get("result") or {}


# ---------------------------------------------------------------------------- 工具定义

def obj(props, required=()):
    return {"type": "object", "properties": props, "required": list(required), "additionalProperties": False}


SELECTOR = obj({
    "label": {"type": "string", "description": "文字或无障碍描述完全等于此值"},
    "label_contains": {"type": "string", "description": "文字或描述包含此值（最常用）"},
    "text": {"type": "string"}, "text_contains": {"type": "string"},
    "desc": {"type": "string"}, "desc_contains": {"type": "string"},
    "id": {"type": "string", "description": "resource-id，可只写 :id/ 后面的短名"},
    "class": {"type": "string", "description": "控件类名，如 EditText、Button"},
    "package": {"type": "string"},
    "clickable": {"type": "boolean"}, "editable": {"type": "boolean"}, "scrollable": {"type": "boolean"},
    "index": {"type": "integer", "minimum": 0, "description": "多个匹配时取第几个（按 find 返回的顺序）"},
})
OBSERVE = {"type": "string", "enum": ["none", "tree", "screenshot", "both"], "default": "none",
           "description": "动作完成后顺带返回的观察；下一步需要看新页面时才设置"}
POINT = {"type": "number", "minimum": 0}

TOOLS = [
    {"name": "android_setup", "description": "配对与连接管理。status 查看当前配对和连通性；pair 用手机 App 里复制的配对信息（androiduse://IP:端口/?token=…）配对；forget 删除本机保存的配对。",
     "inputSchema": obj({"action": {"type": "string", "enum": ["status", "pair", "forget"]},
                         "pairing": {"type": "string"}, "url": {"type": "string"}, "token": {"type": "string"}}, ["action"])},
    {"name": "android_doctor", "description": "逐层诊断：本机配置、网络连通、配对码、无障碍服务、截屏能力、屏幕与锁屏状态，并给出下一步。",
     "inputSchema": obj({})},
    {"name": "android_ready", "description": "本对话第一次操作手机前调用：确认手机在线、服务可用、亮屏且已解锁，并返回当前前台 App 与观察（默认附截图）。ready=true 后直接复用其中的观察开始任务。",
     "inputSchema": obj({"screenshot": {"type": "boolean", "default": True}})},
    {"name": "android_observe", "description": "读取当前屏幕。screenshot 返回截图（最可靠，自绘界面、微信等以它为准）；tree 返回可见控件（文字、描述、id、rect=[x,y,宽,高] 屏幕像素、flags）；both 两者都要。",
     "inputSchema": obj({"mode": {"type": "string", "enum": ["screenshot", "tree", "both"], "default": "screenshot"},
                         "max_nodes": {"type": "integer", "minimum": 10, "maximum": 800, "default": 250},
                         "include_keyboard": {"type": "boolean", "default": False}})},
    {"name": "android_find", "description": "按 selector 查找控件，返回匹配数和候选（含 tap 中心坐标）。只找一个目标时用它，不要为此读整棵树。",
     "inputSchema": obj({"selector": SELECTOR}, ["selector"])},
    {"name": "android_tap", "description": "点击。给 selector 时点匹配控件的中心；或给屏幕像素坐标 x/y（截图像素 × pixel_to_screen）。多个位置不同的匹配会返回 ambiguous_target 和候选。",
     "inputSchema": obj({"selector": SELECTOR, "x": POINT, "y": POINT, "observe": OBSERVE})},
    {"name": "android_long_press", "description": "长按（默认 800 毫秒），用法同 android_tap。",
     "inputSchema": obj({"selector": SELECTOR, "x": POINT, "y": POINT,
                         "duration_ms": {"type": "integer", "minimum": 400, "maximum": 5000, "default": 800}, "observe": OBSERVE})},
    {"name": "android_swipe", "description": "滑动一次。direction 是手指移动方向：up 通常看下面的内容。可用 region=[x,y,宽,高] 限定在某个列表里滑；或直接给 from/to 两个点做拖动。",
     "inputSchema": obj({"direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
                         "region": {"type": "array", "items": {"type": "number"}, "minItems": 4, "maxItems": 4},
                         "distance": {"type": "number", "minimum": 0.1, "maximum": 0.95, "default": 0.6},
                         "from": {"type": "array", "items": {"type": "number"}, "minItems": 2, "maxItems": 2},
                         "to": {"type": "array", "items": {"type": "number"}, "minItems": 2, "maxItems": 2},
                         "duration_ms": {"type": "integer", "minimum": 50, "maximum": 5000, "default": 300},
                         "observe": OBSERVE})},
    {"name": "android_type_text", "description": "向输入框写入完整文字（支持中文和 emoji，不经过输入法）。不给 selector 时写入当前获得焦点的输入框，所以先点一下输入框。默认替换原有内容、不提交；submit=true 触发输入法的回车/搜索/发送动作。",
     "inputSchema": obj({"text": {"type": "string"}, "selector": SELECTOR,
                         "replace": {"type": "boolean", "default": True}, "submit": {"type": "boolean", "default": False},
                         "observe": OBSERVE}, ["text"])},
    {"name": "android_press_key", "description": "系统按键：back、home、recents（最近任务）、notifications、quick_settings、power_dialog、lock_screen，以及 enter（对焦点输入框执行回车动作）。",
     "inputSchema": obj({"name": {"type": "string", "enum": ["back", "home", "recents", "notifications", "quick_settings",
                                                              "power_dialog", "lock_screen", "enter"]}, "observe": OBSERVE}, ["name"])},
    {"name": "android_apps", "description": "列出手机桌面上可启动的 App（名称、包名、版本），可用 query 按名称或包名过滤。",
     "inputSchema": obj({"query": {"type": "string"}})},
    {"name": "android_launch_app", "description": "打开 App：给包名 package（如 com.tencent.mm），或桌面上显示的名称 name（如 微信）。",
     "inputSchema": obj({"package": {"type": "string"}, "name": {"type": "string"}, "observe": OBSERVE})},
    {"name": "android_open_url", "description": "用手机上的默认 App 打开链接或 deep link（https://…、tel:、geo: 等）。",
     "inputSchema": obj({"url": {"type": "string"}, "observe": OBSERVE}, ["url"])},
    {"name": "android_wait", "description": "等待某个控件出现（或 gone=true 时等它消失），最多 timeout 秒。",
     "inputSchema": obj({"selector": SELECTOR, "timeout": {"type": "number", "minimum": 0.5, "maximum": 60, "default": 10},
                         "gone": {"type": "boolean", "default": False}}, ["selector"])},
    {"name": "android_scroll_find", "description": "边滑边找：目标已在屏幕上就直接返回；否则按 direction 滑一次再找，最多 max_swipes 次，页面不再变化就停下并附截图。",
     "inputSchema": obj({"selector": SELECTOR, "direction": {"type": "string", "enum": ["up", "down", "left", "right"], "default": "up"},
                         "region": {"type": "array", "items": {"type": "number"}, "minItems": 4, "maxItems": 4},
                         "max_swipes": {"type": "integer", "minimum": 0, "maximum": 10, "default": 4}}, ["selector"])},
    {"name": "android_batch", "description": "按顺序执行一串已知步骤（最多 20 步），遇到错误就停并报告停在哪一步。每步形如 {\"tool\":\"tap\",\"args\":{…}}，tool 可选 tap、long_press、swipe、type_text、press_key、launch_app、open_url、wait、sleep（args.ms）。",
     "inputSchema": obj({"steps": {"type": "array", "minItems": 1, "maxItems": 20, "items": obj(
         {"tool": {"type": "string", "enum": ["tap", "long_press", "swipe", "type_text", "press_key", "launch_app", "open_url", "wait", "sleep"]},
          "args": {"type": "object"}}, ["tool"])}, "observe": OBSERVE}, ["steps"])},
]
TOOL_NAMES = {t["name"] for t in TOOLS}


# ---------------------------------------------------------------------------- 运行时

class Runtime:
    def __init__(self):
        self.state = state_dir()
        self.screen = None  # [宽, 高]，屏幕像素

    # -- 通道
    def phone(self):
        cfg = load_config()
        if not cfg:
            raise AUError("not_paired", "还没有配对手机。",
                          "在手机 Android Use 里打开远程控制并复制配对信息，然后调用 android_setup(action=\"pair\", pairing=…)。")
        return Phone(cfg["url"], cfg["token"])

    def rpc(self, method, params=None, timeout=20):
        return self.phone().rpc(method, params, timeout)

    # -- 观察
    def save_image(self, b64, mime):
        art = self.state / "artifacts"
        art.mkdir(exist_ok=True)
        os.chmod(art, 0o700)
        stamp = time.strftime("%Y%m%dT%H%M%S") + f"-{int(time.time() * 1000) % 1000:03d}"
        path = art / f"{stamp}.jpg"
        write_private(path, base64.b64decode(b64))
        old = sorted(art.glob("*.jpg"))[:-KEEP_ARTIFACTS]
        for p in old:
            try:
                p.unlink()
            except OSError:
                pass
        return str(path)

    def observe(self, mode="screenshot", max_nodes=250, include_keyboard=False):
        out, images = {}, []
        if mode in ("tree", "both"):
            t = self.rpc("tree", {"max_nodes": max_nodes, "keyboard": include_keyboard})
            self.screen = t.get("screen") or self.screen
            out.update({"foreground": t.get("foreground"), "screen": t.get("screen"), "nodes": t.get("nodes", []),
                        "truncated": t.get("truncated", False)})
        if mode in ("screenshot", "both"):
            s = self.rpc("screenshot", {"max_side": 1280, "quality": 72}, timeout=30)
            self.screen = [s["screen_width"], s["screen_height"]]
            path = self.save_image(s["data"], s.get("mime", "image/jpeg"))
            out.setdefault("foreground", s.get("foreground"))
            out["screen"] = self.screen
            out["image"] = {"path": path, "width": s["width"], "height": s["height"],
                            "pixel_to_screen": [round(s["screen_width"] / s["width"], 4), round(s["screen_height"] / s["height"], 4)]}
            images.append({"type": "image", "data": s["data"], "mimeType": s.get("mime", "image/jpeg")})
        return out, images

    def wait_stable(self, limit=2.5):
        """动作之后等页面动画、跳转结束：连续两次读到相同的界面就算稳定。"""
        time.sleep(SETTLE_SECONDS)
        deadline, last = time.monotonic() + limit, None
        while time.monotonic() < deadline:
            t = self.rpc("tree", {"max_nodes": 80})
            sig = json.dumps([t.get("foreground")] + [(n.get("text"), n.get("desc"), n.get("rect")) for n in t.get("nodes", [])],
                             ensure_ascii=False)
            if sig == last:
                return
            last = sig
            time.sleep(0.3)

    def after(self, result, observe):
        if observe in (None, "none"):
            return result, []
        self.wait_stable()
        obs, images = self.observe(observe)
        result["observation"] = obs
        return result, images

    def screen_size(self):
        if not self.screen:
            st = self.rpc("status")
            self.screen = [st["screen"]["width"], st["screen"]["height"]]
        return self.screen

    # -- 工具
    def call(self, name, args):
        if name not in TOOL_NAMES:
            raise AUError("unknown_tool", f"没有这个工具：{name}")
        return getattr(self, "t_" + name[len("android_"):])(**args)

    def t_setup(self, action, pairing=None, url=None, token=None):
        path = self.state / "config.json"
        if action == "forget":
            existed = path.exists()
            if existed:
                path.unlink()
            return {"forgotten": existed}, []
        if action == "pair":
            url, token = parse_pairing(pairing, url, token)
            phone = Phone(url, token)
            ping = phone.ping()
            st = phone.rpc("status")
            cfg = {"url": url, "token": token, "paired_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                   "device": f'{st.get("manufacturer", "")} {st.get("model", "")}'.strip(), "android": st.get("android")}
            write_private(path, json.dumps(cfg, ensure_ascii=False, indent=2).encode())
            return {"paired": True, "url": url, "device": cfg["device"], "android": cfg["android"],
                    "app_version": ping.get("version"), "next_step": "调用 android_ready 开始操作。"}, []
        cfg = load_config()
        if not cfg:
            return {"paired": False, "next_step": "按 android-use-setup 技能在手机上安装并开启 App，然后 pair。"}, []
        info = {"paired": True, "url": cfg["url"], "device": cfg.get("device"), "source": cfg.get("source", "config"),
                "token": cfg["token"][:4] + "…"}
        try:
            info["app_version"] = Phone(cfg["url"], cfg["token"]).ping(timeout=4).get("version")
            info["reachable"] = True
        except AUError as e:
            info["reachable"] = False
            info["error"] = e.as_dict()
        return info, []

    def t_doctor(self):
        checks, steps = {}, []
        checks["python"] = sys.version.split()[0]
        cfg = load_config()
        checks["paired"] = bool(cfg)
        if not cfg:
            steps.append("在手机上安装 Android Use，开启无障碍服务和「允许 Agent 远程控制」，复制配对信息后调用 android_setup(action=\"pair\")。")
            return {"ok": False, "checks": checks, "next_steps": steps}, []
        phone = Phone(cfg["url"], cfg["token"])
        checks["url"] = cfg["url"]
        try:
            t0 = time.monotonic()
            ping = phone.ping()
            checks["reachable"] = True
            checks["latency_ms"] = round((time.monotonic() - t0) * 1000)
            checks["app_version"] = ping.get("version")
        except AUError as e:
            checks["reachable"] = False
            checks["network_error"] = e.as_dict()
            steps.append(e.hint or e.message)
            return {"ok": False, "checks": checks, "next_steps": steps}, []
        try:
            st = phone.rpc("status")
            checks["authorized"] = True
        except AUError as e:
            checks["authorized"] = False
            steps.append(e.hint or e.message)
            return {"ok": False, "checks": checks, "next_steps": steps}, []
        checks.update({"device": f'{st.get("manufacturer")} {st.get("model")}', "android": st.get("android"),
                       "screen_on": st.get("interactive"), "unlocked": not st.get("locked"),
                       "foreground": st.get("foreground"), "screenshot_supported": st.get("capabilities", {}).get("screenshot")})
        if not st.get("interactive"):
            steps.append("手机屏幕是灭的：请点亮屏幕。")
        if st.get("locked"):
            steps.append("手机在锁屏界面：请本人解锁。")
        if not checks["screenshot_supported"]:
            steps.append("截屏需要 Android 11 及以上；本机只能读界面树。")
        return {"ok": not steps, "checks": checks, "next_steps": steps or ["一切正常，可以调用 android_ready。"]}, []

    def t_ready(self, screenshot=True):
        st = self.rpc("status")
        self.screen = [st["screen"]["width"], st["screen"]["height"]]
        proof = {"connected": True, "service": True, "screen_on": bool(st.get("interactive")),
                 "unlocked": not st.get("locked"), "foreground": st.get("foreground"),
                 "keyboard_visible": st.get("keyboard_visible"), "screen": self.screen,
                 "device": f'{st.get("manufacturer")} {st.get("model")}', "android": st.get("android")}
        if not proof["screen_on"] or not proof["unlocked"]:
            return {"ready": False, "state": "needs_user", "proof": proof,
                    "next_step": "请用户本人点亮并解锁手机（无法远程解锁），完成后再调用 android_ready。"}, []
        obs, images = self.observe("both" if screenshot else "tree", max_nodes=150)
        return {"ready": True, "state": "ready", "proof": proof, "observation": obs}, images

    def t_observe(self, mode="screenshot", max_nodes=250, include_keyboard=False):
        return self.observe(mode, max_nodes, include_keyboard)

    def t_find(self, selector):
        return self.rpc("find", {"selector": selector}), []

    def _point_or_selector(self, selector, x, y):
        if selector:
            return {"selector": selector}
        if x is None or y is None:
            raise AUError("invalid_argument", "需要 selector，或同时给出 x 和 y（屏幕像素）。")
        return {"x": x, "y": y}

    def t_tap(self, selector=None, x=None, y=None, observe="none"):
        return self.after(self.rpc("tap", self._point_or_selector(selector, x, y)), observe)

    def t_long_press(self, selector=None, x=None, y=None, duration_ms=800, observe="none"):
        params = self._point_or_selector(selector, x, y)
        params["duration_ms"] = duration_ms
        return self.after(self.rpc("long_press", params), observe)

    def _swipe_points(self, direction, region, distance):
        w, h = self.screen_size()
        if region:
            rx, ry, rw, rh = region
        else:
            rx, ry, rw, rh = w * 0.1, h * 0.22, w * 0.8, h * 0.56
        cx, cy = rx + rw / 2, ry + rh / 2
        dx, dy = rw * distance / 2, rh * distance / 2
        return {"up": (cx, cy + dy, cx, cy - dy), "down": (cx, cy - dy, cx, cy + dy),
                "left": (cx + dx, cy, cx - dx, cy), "right": (cx - dx, cy, cx + dx, cy)}[direction]

    def t_swipe(self, direction=None, region=None, distance=0.6, duration_ms=300, observe="none", **kw):
        start, end = kw.get("from"), kw.get("to")
        if start and end:
            x1, y1, x2, y2 = start[0], start[1], end[0], end[1]
        elif direction:
            x1, y1, x2, y2 = self._swipe_points(direction, region, distance)
        else:
            raise AUError("invalid_argument", "需要 direction，或同时给 from 和 to。")
        res = self.rpc("swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": duration_ms})
        return self.after(res, observe)

    def t_type_text(self, text, selector=None, replace=True, submit=False, observe="none"):
        params = {"text": text, "replace": replace, "submit": submit}
        if selector:
            params["selector"] = selector
        # 刚点完输入框时，页面跳转动画、键盘弹出还没完成：要么焦点还没到位（no_focused_field），
        # 要么输入框已获焦点却暂时拒绝写入（input_failed）。两种情况都一个字没写进去，
        # 短暂等待后重试没有副作用；batch 里"点输入框 → 输入"连着执行时最常见。
        deadline = time.monotonic() + FOCUS_WAIT_SECONDS
        while True:
            try:
                return self.after(self.rpc("type", params, timeout=30), observe)
            except AUError as e:
                if e.code not in ("no_focused_field", "input_failed") or time.monotonic() >= deadline:
                    raise
                time.sleep(0.25)

    def t_press_key(self, name, observe="none"):
        return self.after(self.rpc("key", {"name": name}), observe)

    def t_apps(self, query=None):
        return self.rpc("apps", {"query": query or ""}), []

    def t_launch_app(self, package=None, name=None, observe="none"):
        if not package and not name:
            raise AUError("invalid_argument", "需要 package 或 name。")
        params = {"package": package} if package else {"name": name}
        res = self.rpc("launch", params)
        # 等目标 App 真正到前台（冷启动常要 1~3 秒）：否则"页面稳定"会在桌面上提前成立，
        # 紧接着的点按（batch 里最常见）就落在桌面上。
        # 刚按过主页键时，新启动的 App 偶尔会被回桌面的动画盖到桌面后面、再也不出来：
        # 2.5 秒还没到前台就再发一次启动（App 已在运行时只是切到前台，没有副作用）。
        # 跳到别的包的 App 等满时限也照常继续，由下一步观察判断。
        target = res.get("package")
        res["foreground_verified"] = False
        for attempt in range(2 if target else 0):
            deadline = time.monotonic() + (2.5 if attempt == 0 else LAUNCH_WAIT_SECONDS)
            while time.monotonic() < deadline:
                if self.rpc("status").get("foreground") == target:
                    res["foreground_verified"] = True
                    break
                time.sleep(0.2)
            if res["foreground_verified"] or attempt == 1:
                break
            self.rpc("launch", {"package": target})
            res["relaunched"] = True
        return self.after(res, observe)

    def t_open_url(self, url, observe="none"):
        return self.after(self.rpc("open_url", {"url": url}), observe)

    def t_wait(self, selector, timeout=10, gone=False):
        deadline = time.monotonic() + timeout
        while True:
            found = self.rpc("find", {"selector": selector})
            present = found.get("count", 0) > 0
            if present != gone:
                return {"satisfied": True, "count": found.get("count", 0), "matches": found.get("matches", [])[:5]}, []
            if time.monotonic() >= deadline:
                raise AUError("wait_timeout", f"{timeout} 秒内{'没有消失' if gone else '没有出现'}。")
            time.sleep(0.5)

    def t_scroll_find(self, selector, direction="up", region=None, max_swipes=4):
        last_sig = None
        for attempt in range(max_swipes + 1):
            found = self.rpc("find", {"selector": selector})
            if found.get("count", 0) > 0:
                return {"found": True, "swipes": attempt, "matches": found["matches"][:5]}, []
            if attempt == max_swipes:
                break
            tree = self.rpc("tree", {"max_nodes": 120})
            sig = json.dumps([(n.get("text"), n.get("desc"), n.get("rect")) for n in tree.get("nodes", [])], ensure_ascii=False)
            if sig == last_sig:
                obs, images = self.observe("screenshot")
                return {"found": False, "swipes": attempt, "stop_reason": "no_progress",
                        "next_step": "页面已不再变化：看截图判断是否到底、被遮挡或滑错了区域。", "observation": obs}, images
            last_sig = sig
            self.t_swipe(direction=direction, region=region)
            time.sleep(0.5)
        obs, images = self.observe("screenshot")
        return {"found": False, "swipes": max_swipes, "stop_reason": "max_swipes", "observation": obs}, images

    def t_batch(self, steps, observe="none"):
        done = []
        for i, step in enumerate(steps):
            tool, args = step.get("tool"), dict(step.get("args") or {})
            try:
                if tool == "sleep":
                    time.sleep(min(10, max(0, args.get("ms", 500))) / 1000)
                    res = {"slept_ms": args.get("ms", 500)}
                else:
                    args.pop("observe", None)
                    res, _ = self.call("android_" + tool, args)
            except (AUError, TypeError) as e:
                err = e.as_dict() if isinstance(e, AUError) else {"code": "invalid_argument", "message": str(e)}
                result = {"completed_steps": i, "stopped_at": i, "error": err, "results": done}
                obs, images = self.observe("screenshot")
                result["observation"] = obs
                return result, images
            done.append({"step": i, "tool": tool, "result": res})
        return self.after({"completed_steps": len(steps), "results": done}, observe)


# ---------------------------------------------------------------------------- MCP stdio

def tool_result(runtime, params):
    name, args = params.get("name"), params.get("arguments") or {}
    try:
        if not isinstance(args, dict):
            raise AUError("invalid_argument", "arguments 必须是对象。")
        payload, images = runtime.call(name, args)
        is_error = False
    except AUError as e:
        payload, images, is_error = {"error": e.as_dict()}, [], True
    except TypeError as e:
        payload, images, is_error = {"error": {"code": "invalid_argument", "message": str(e)}}, [], True
    except Exception as e:  # 兜底，不能让 MCP 进程退出
        payload, images, is_error = {"error": {"code": "internal_error", "message": repr(e)}}, [], True
    content = [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}] + images
    return {"content": content, "isError": is_error}


def serve(runtime):
    def send(msg):
        sys.stdout.write(json.dumps(msg, ensure_ascii=False) + "\n")
        sys.stdout.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            send({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})
            continue
        if not isinstance(req, dict) or "id" not in req:
            continue  # 通知不需要回复
        ident, method, params = req.get("id"), req.get("method"), req.get("params") or {}
        if method == "initialize":
            offered = params.get("protocolVersion")
            result = {"protocolVersion": offered if offered in PROTOCOLS else PROTOCOLS[0],
                      "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": {"name": "android-use", "version": VERSION}, "instructions": INSTRUCTIONS}
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            result = tool_result(runtime, params)
        else:
            send({"jsonrpc": "2.0", "id": ident, "error": {"code": -32601, "message": "Method not found"}})
            continue
        send({"jsonrpc": "2.0", "id": ident, "result": result})


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd")
    c = sub.add_parser("call", help="不经过 MCP 直接调用一个工具，例如 call android_ready '{}'")
    c.add_argument("tool")
    c.add_argument("arguments", nargs="?", default="{}")
    sub.add_parser("doctor")
    args = parser.parse_args()
    runtime = Runtime()
    if args.cmd in ("call", "doctor"):
        name = "android_doctor" if args.cmd == "doctor" else args.tool
        if not name.startswith("android_"):
            name = "android_" + name
        result = tool_result(runtime, {"name": name, "arguments": json.loads(args.arguments) if args.cmd == "call" else {}})
        payload = json.loads(result["content"][0]["text"])
        print(json.dumps(payload, ensure_ascii=False, indent=1))
        return 1 if result["isError"] else 0
    serve(runtime)
    return 0


if __name__ == "__main__":
    sys.exit(main())
