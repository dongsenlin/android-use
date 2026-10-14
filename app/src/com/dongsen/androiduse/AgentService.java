package com.dongsen.androiduse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 核心：一个无障碍服务。用户在系统设置里亲手开启后，它才能截屏、读界面、做手势；
 * 再加上 App 里的"允许 Agent 远程控制"开关，才会在 Wi‑Fi 上开放带配对码的 HTTP 接口。
 */
public class AgentService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    static final String VERSION = "0.1.1";
    static volatile AgentService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object actionLock = new Object();
    private HttpServer http;
    private Overlay overlay;
    private volatile String serverError;
    private volatile String lastPackage = "";
    private volatile String lastActivity = "";

    static final class RpcError extends Exception {
        final String code;
        final JSONObject details;

        RpcError(String code, String message) {
            this(code, message, null);
        }

        RpcError(String code, String message, JSONObject details) {
            super(message);
            this.code = code;
            this.details = details;
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onServiceConnected() {
        instance = this;
        overlay = new Overlay(this);
        Prefs.token(this);
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this);
        syncServer();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || pkg.toString().equals(getPackageName())) return;
        lastPackage = pkg.toString();
        CharSequence cls = event.getClassName();
        lastActivity = cls == null ? "" : cls.toString();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this);
        stopServer();
        instance = null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (Prefs.KEY_ENABLED.equals(key) || Prefs.KEY_PORT.equals(key)) syncServer();
    }

    synchronized void syncServer() {
        boolean want = Prefs.enabled(this);
        if (want && http == null) {
            HttpServer s = new HttpServer(Prefs.port(this), this::handle);
            try {
                s.start();
                http = s;
                serverError = null;
                overlay.showPill();
            } catch (Exception e) {
                serverError = "端口 " + Prefs.port(this) + " 无法监听：" + e.getMessage();
            }
        } else if (!want) {
            stopServer();
        }
    }

    private synchronized void stopServer() {
        if (http != null) {
            http.stop();
            http = null;
        }
        if (overlay != null) overlay.hidePill();
    }

    boolean serverRunning() {
        return http != null && http.isRunning();
    }

    String serverError() {
        return serverError;
    }

    // ---------------------------------------------------------------- HTTP 入口

    private HttpServer.Response handle(HttpServer.Request r) throws Exception {
        if (!isLocalClient(r.remote)) {
            return HttpServer.Response.json(403, error("forbidden", "只接受局域网或本机的连接。").toString());
        }
        if ("GET".equals(r.method) && "/v1/ping".equals(r.path)) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("app", "android-use");
            o.put("version", VERSION);
            o.put("auth", "bearer");
            return HttpServer.Response.json(200, o.toString());
        }
        if (!authorized(r)) {
            Thread.sleep(300);
            return HttpServer.Response.json(401, error("unauthorized", "配对码不对或缺失，请在电脑上重新配对。").toString());
        }
        if (!"POST".equals(r.method) || !"/v1/rpc".equals(r.path)) {
            return HttpServer.Response.json(404, error("not_found", "Unknown endpoint " + r.path).toString());
        }
        JSONObject req;
        try {
            req = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return HttpServer.Response.json(400, error("bad_request", "Body must be a JSON object.").toString());
        }
        String method = req.optString("method", "");
        JSONObject params = req.optJSONObject("params");
        if (params == null) params = new JSONObject();
        if (overlay != null) overlay.touch();
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("result", dispatch(method, params));
        } catch (RpcError e) {
            out = error(e.code, e.getMessage());
            if (e.details != null) out.getJSONObject("error").put("details", e.details);
        } catch (JSONException e) {
            out = error("invalid_argument", e.getMessage());
        } catch (InterruptedException e) {
            // 远程控制在手机上被关掉时，服务会打断还没做完的操作
            out = error("remote_paused", "手机上的远程控制刚被关闭（点了顶部紫色提示条，或在 App 里关了开关），这一步已中止。");
        }
        return HttpServer.Response.json(200, out.toString());
    }

    private boolean authorized(HttpServer.Request r) {
        String h = r.headers.get("authorization");
        if (h == null || !h.startsWith("Bearer ")) return false;
        byte[] given = h.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = Prefs.token(this).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, expected);
    }

    private static boolean isLocalClient(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            return (b[0] & 0xfe) == 0xfc; // fc00::/7 唯一本地地址
        }
        return false;
    }

    private static JSONObject error(String code, String message) {
        JSONObject o = new JSONObject();
        try {
            JSONObject e = new JSONObject();
            e.put("code", code);
            e.put("message", message == null ? code : message);
            o.put("ok", false);
            o.put("error", e);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private JSONObject dispatch(String method, JSONObject p) throws Exception {
        switch (method) {
            case "status":
                return status();
            case "tree":
                return tree(p);
            case "find":
                return find(p);
            case "apps":
                return apps(p);
            default:
                break;
        }
        // 会改变手机状态或受截屏频率限制的调用一律串行
        synchronized (actionLock) {
            switch (method) {
                case "screenshot":
                    return screenshot(p);
                case "tap":
                    return tap(p, false);
                case "long_press":
                    return tap(p, true);
                case "swipe":
                    return swipe(p);
                case "key":
                    return key(p);
                case "type":
                    return typeText(p);
                case "launch":
                    return launch(p);
                case "open_url":
                    return openUrl(p);
                default:
                    throw new RpcError("unknown_method", "Unknown method: " + method);
            }
        }
    }

    // ---------------------------------------------------------------- 状态

    private int[] screenSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Rect b = wm.getMaximumWindowMetrics().getBounds();
        return new int[]{b.width(), b.height()};
    }

    private JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("app_version", VERSION);
        o.put("android", Build.VERSION.RELEASE);
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("model", Build.MODEL);
        int[] size = screenSize();
        JSONObject screen = new JSONObject();
        screen.put("width", size[0]);
        screen.put("height", size[1]);
        screen.put("density", getResources().getDisplayMetrics().density);
        Display d = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        screen.put("rotation", d.getRotation() * 90);
        o.put("screen", screen);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        o.put("interactive", pm.isInteractive());
        o.put("locked", km.isKeyguardLocked());
        o.put("foreground", foregroundPackage());
        o.put("activity", lastActivity);
        boolean ime = false;
        JSONArray windows = new JSONArray();
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) ime = true;
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            JSONObject wo = new JSONObject();
            wo.put("type", windowType(w.getType()));
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null && root.getPackageName() != null) wo.put("package", root.getPackageName().toString());
            if (w.getTitle() != null) wo.put("title", w.getTitle().toString());
            if (w.isActive()) wo.put("active", true);
            windows.put(wo);
        }
        o.put("keyboard_visible", ime);
        o.put("windows", windows);
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        JSONObject caps = new JSONObject();
        caps.put("screenshot", Build.VERSION.SDK_INT >= 30);
        caps.put("gestures", true);
        caps.put("ime_enter", Build.VERSION.SDK_INT >= 30);
        o.put("capabilities", caps);
        return o;
    }

    private String foregroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) {
            String pkg = root.getPackageName().toString();
            if (!pkg.equals(getPackageName()) || lastPackage.isEmpty()) return pkg;
            return pkg;
        }
        return lastPackage;
    }

    private static String windowType(int t) {
        switch (t) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "app";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "keyboard";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "divider";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "overlay";
            default: return "other";
        }
    }

    // ---------------------------------------------------------------- 截屏

    private JSONObject screenshot(JSONObject p) throws Exception {
        if (Build.VERSION.SDK_INT < 30) throw new RpcError("unsupported", "截屏需要 Android 11 及以上。");
        int maxSide = Math.max(320, Math.min(2400, p.optInt("max_side", 1280)));
        int quality = Math.max(30, Math.min(95, p.optInt("quality", 72)));
        Bitmap full;
        if (overlay != null) overlay.setCaptureHidden(true);
        try {
            Thread.sleep(80);
            full = capture();
        } finally {
            if (overlay != null) overlay.setCaptureHidden(false);
        }
        int w = full.getWidth(), h = full.getHeight();
        float scale = Math.min(1f, maxSide / (float) Math.max(w, h));
        Bitmap out = scale < 1f ? Bitmap.createScaledBitmap(full, Math.round(w * scale), Math.round(h * scale), true) : full;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        JSONObject o = new JSONObject();
        o.put("mime", "image/jpeg");
        o.put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
        o.put("width", out.getWidth());
        o.put("height", out.getHeight());
        o.put("screen_width", w);
        o.put("screen_height", h);
        o.put("foreground", foregroundPackage());
        if (out != full) out.recycle();
        full.recycle();
        return o;
    }

    private Bitmap capture() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            final CountDownLatch latch = new CountDownLatch(1);
            final Bitmap[] result = new Bitmap[1];
            final int[] failure = {0};
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    HardwareBuffer hb = r.getHardwareBuffer();
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null) {
                            result[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                        }
                    } finally {
                        hb.close();
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    failure[0] = errorCode;
                    latch.countDown();
                }
            });
            if (!latch.await(6, TimeUnit.SECONDS)) throw new RpcError("screenshot_timeout", "截屏超时。");
            if (result[0] != null) return result[0];
            if (failure[0] == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                Thread.sleep(360);
                continue;
            }
            String why;
            switch (failure[0]) {
                case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS: why = "没有截屏权限，请重新开启无障碍服务"; break;
                case 6: why = "当前页面禁止截屏（FLAG_SECURE，常见于支付、银行、密码页）"; break;
                default: why = "系统截屏失败，错误码 " + failure[0]; break;
            }
            throw new RpcError("screenshot_failed", why);
        }
        throw new RpcError("screenshot_failed", "截屏太频繁，系统多次拒绝。");
    }

    // ---------------------------------------------------------------- 界面树

    private static final class Item {
        AccessibilityNodeInfo node;
        int window;
        String pkg;
        Rect rect = new Rect();
    }

    /** 自上而下（最上层窗口优先）收集可见节点。 */
    private List<Item> collect(boolean includeKeyboard, int limit) {
        // 系统会在本进程缓存节点；滚动、刷新后若事件还没送到，缓存就是旧的。安卓 13 起可以直接清掉。
        if (Build.VERSION.SDK_INT >= 33) clearCache();
        List<Item> items = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = new ArrayList<>(getWindows());
        Collections.sort(windows, (a, b) -> b.getLayer() - a.getLayer());
        int wi = 0;
        for (AccessibilityWindowInfo w : windows) {
            int type = w.getType();
            if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && !includeKeyboard) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            walk(root, wi, pkg, items, limit, 0);
            wi++;
            if (items.size() >= limit) break;
        }
        return items;
    }

    private void walk(AccessibilityNodeInfo n, int window, String pkg, List<Item> out, int limit, int depth) {
        if (n == null || out.size() >= limit || depth > 80) return;
        if (n.isVisibleToUser()) {
            Item it = new Item();
            it.node = n;
            it.window = window;
            it.pkg = pkg;
            n.getBoundsInScreen(it.rect);
            if (it.rect.width() > 0 && it.rect.height() > 0) out.add(it);
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), window, pkg, out, limit, depth + 1);
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String shortId(String id) {
        int i = id.indexOf(":id/");
        return i >= 0 ? id.substring(i + 4) : id;
    }

    private static String shortClass(CharSequence cls) {
        String c = str(cls);
        int i = c.lastIndexOf('.');
        return i >= 0 ? c.substring(i + 1) : c;
    }

    private static boolean interesting(AccessibilityNodeInfo n) {
        return n.getText() != null && n.getText().length() > 0
                || n.getContentDescription() != null && n.getContentDescription().length() > 0
                || n.isClickable() || n.isLongClickable() || n.isEditable() || n.isScrollable() || n.isCheckable();
    }

    private JSONObject describe(Item it, int index) throws JSONException {
        AccessibilityNodeInfo n = it.node;
        JSONObject o = new JSONObject();
        o.put("i", index);
        o.put("cls", shortClass(n.getClassName()));
        String text = str(n.getText());
        boolean hint = Build.VERSION.SDK_INT >= 26 && n.isShowingHintText();
        if (n.isPassword() && !text.isEmpty() && !hint) text = "<密码已隐藏>";
        if (!text.isEmpty()) o.put(hint ? "hint" : "text", clip(text, 240));
        String desc = str(n.getContentDescription());
        if (!desc.isEmpty() && !desc.equals(text)) o.put("desc", clip(desc, 240));
        if (n.getViewIdResourceName() != null) o.put("id", shortId(n.getViewIdResourceName()));
        Rect r = it.rect;
        o.put("rect", new JSONArray().put(r.left).put(r.top).put(r.width()).put(r.height()));
        StringBuilder f = new StringBuilder();
        if (n.isClickable()) f.append("click,");
        if (n.isLongClickable()) f.append("long,");
        if (n.isEditable()) f.append("edit,");
        if (n.isScrollable()) f.append("scroll,");
        if (n.isCheckable()) f.append(n.isChecked() ? "checked," : "unchecked,");
        if (n.isSelected()) f.append("selected,");
        if (n.isFocused()) f.append("focused,");
        if (!n.isEnabled()) f.append("disabled,");
        if (f.length() > 0) o.put("flags", f.substring(0, f.length() - 1));
        if (it.window > 0) o.put("win", it.window);
        return o;
    }

    private JSONObject tree(JSONObject p) throws JSONException {
        int maxNodes = Math.max(10, Math.min(800, p.optInt("max_nodes", 250)));
        boolean all = p.optBoolean("all", false);
        List<Item> items = collect(p.optBoolean("keyboard", false), 4000);
        JSONArray nodes = new JSONArray();
        int kept = 0;
        boolean truncated = false;
        for (Item it : items) {
            if (!all && !interesting(it.node)) continue;
            if (kept >= maxNodes) {
                truncated = true;
                break;
            }
            nodes.put(describe(it, kept));
            kept++;
        }
        JSONObject o = new JSONObject();
        o.put("foreground", foregroundPackage());
        int[] size = screenSize();
        o.put("screen", new JSONArray().put(size[0]).put(size[1]));
        o.put("nodes", nodes);
        o.put("truncated", truncated);
        return o;
    }

    // ---------------------------------------------------------------- 查找

    private static boolean has(JSONObject s, String k) {
        return s.has(k) && !s.isNull(k);
    }

    private boolean matches(Item it, JSONObject s) {
        AccessibilityNodeInfo n = it.node;
        String text = str(n.getText());
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) text = "";
        String desc = str(n.getContentDescription());
        String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
        if (has(s, "label") && !(s.optString("label").equals(text) || s.optString("label").equals(desc))) return false;
        if (has(s, "label_contains")) {
            String q = s.optString("label_contains");
            if (!(text.contains(q) || desc.contains(q))) return false;
        }
        if (has(s, "text") && !s.optString("text").equals(text)) return false;
        if (has(s, "text_contains") && !text.contains(s.optString("text_contains"))) return false;
        if (has(s, "desc") && !s.optString("desc").equals(desc)) return false;
        if (has(s, "desc_contains") && !desc.contains(s.optString("desc_contains"))) return false;
        if (has(s, "id")) {
            String want = s.optString("id");
            if (!(id.equals(want) || shortId(id).equals(want))) return false;
        }
        if (has(s, "class")) {
            String want = s.optString("class");
            String cls = str(n.getClassName());
            if (!(cls.equals(want) || shortClass(cls).equals(want))) return false;
        }
        if (has(s, "package") && !s.optString("package").equals(it.pkg)) return false;
        if (has(s, "clickable") && s.optBoolean("clickable") != n.isClickable()) return false;
        if (has(s, "editable") && s.optBoolean("editable") != n.isEditable()) return false;
        if (has(s, "scrollable") && s.optBoolean("scrollable") != n.isScrollable()) return false;
        return true;
    }

    private List<Item> query(JSONObject selector) throws RpcError {
        if (selector == null || selector.length() == 0) throw new RpcError("invalid_selector", "selector 不能为空。");
        List<Item> out = new ArrayList<>();
        for (Item it : collect(true, 6000)) if (matches(it, selector)) out.add(it);
        return out;
    }

    private JSONObject candidate(Item it, int index) throws JSONException {
        JSONObject o = describe(it, index);
        o.put("tap", new JSONArray().put(it.rect.centerX()).put(it.rect.centerY()));
        return o;
    }

    private JSONObject find(JSONObject p) throws Exception {
        List<Item> found = query(p.getJSONObject("selector"));
        JSONArray arr = new JSONArray();
        for (int i = 0; i < found.size() && i < 30; i++) arr.put(candidate(found.get(i), i));
        JSONObject o = new JSONObject();
        o.put("count", found.size());
        o.put("matches", arr);
        return o;
    }

    private Item resolveOne(JSONObject selector) throws Exception {
        List<Item> found = query(selector);
        if (found.isEmpty()) throw new RpcError("no_such_element", "没有找到匹配的控件。");
        if (selector.has("index")) {
            int idx = selector.getInt("index");
            if (idx < 0 || idx >= found.size()) throw new RpcError("no_such_element", "index 超出匹配数量 " + found.size());
            return found.get(idx);
        }
        if (found.size() == 1) return found.get(0);
        // 嵌套在同一位置的多个节点（如按钮和里面的文字）视为同一个目标
        Rect first = found.get(0).rect;
        boolean same = true;
        for (Item it : found) {
            if (Math.abs(it.rect.centerX() - first.centerX()) > 4 || Math.abs(it.rect.centerY() - first.centerY()) > 4) {
                same = false;
                break;
            }
        }
        if (same) return found.get(0);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < found.size() && i < 12; i++) arr.put(candidate(found.get(i), i));
        JSONObject d = new JSONObject();
        d.put("candidates", arr);
        throw new RpcError("ambiguous_target", "匹配到 " + found.size() + " 个位置不同的控件，请加 index 或改用坐标。", d);
    }

    // ---------------------------------------------------------------- 手势

    private boolean gesture(Path path, long durationMs) throws InterruptedException {
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, Math.max(1, durationMs));
        final GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] completed = {false};
        main.post(() -> {
            boolean accepted = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    completed[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription d) {
                    latch.countDown();
                }
            }, main);
            if (!accepted) latch.countDown();
        });
        latch.await(durationMs + 4000, TimeUnit.MILLISECONDS);
        return completed[0];
    }

    private float[] clampPoint(double x, double y) {
        int[] size = screenSize();
        return new float[]{(float) Math.max(0, Math.min(size[0] - 1, x)), (float) Math.max(0, Math.min(size[1] - 1, y))};
    }

    /** 顶部提示条是手机主人的暂停键，截图里又看不到它；Agent 的手势不许落在上面。 */
    private void ensureNotOnPill(float[] pt) throws Exception {
        Rect r = overlay == null ? null : overlay.pillAt(pt[0], pt[1]);
        if (r == null) return;
        JSONObject d = new JSONObject();
        d.put("rect", new JSONArray().put(r.left).put(r.top).put(r.width()).put(r.height()));
        throw new RpcError("blocked_by_overlay",
                "这个位置被手机顶部的 Android Use 提示条挡着（截图里看不到它，点它会暂停远程控制）。请换一个不在 details.rect 里的点，或先把页面滑一下。", d);
    }

    private JSONObject tap(JSONObject p, boolean longPress) throws Exception {
        double x, y;
        JSONObject o = new JSONObject();
        if (p.has("selector")) {
            Item it = resolveOne(p.getJSONObject("selector"));
            x = it.rect.centerX();
            y = it.rect.centerY();
            o.put("target", describe(it, 0));
        } else if (p.has("x") && p.has("y")) {
            x = p.getDouble("x");
            y = p.getDouble("y");
        } else {
            throw new RpcError("invalid_argument", "需要 selector 或 x/y。");
        }
        float[] pt = clampPoint(x, y);
        ensureNotOnPill(pt);
        Path path = new Path();
        path.moveTo(pt[0], pt[1]);
        if (overlay != null) overlay.ripple(pt[0], pt[1]);
        long duration = longPress ? Math.max(400, p.optLong("duration_ms", 800)) : 60;
        boolean ok = gesture(path, duration);
        if (!ok) throw new RpcError("gesture_cancelled", "系统取消了这次手势（可能被弹窗或锁屏打断）。");
        o.put("point", new JSONArray().put(Math.round(pt[0])).put(Math.round(pt[1])));
        return o;
    }

    private JSONObject swipe(JSONObject p) throws Exception {
        float[] a = clampPoint(p.getDouble("x1"), p.getDouble("y1"));
        float[] b = clampPoint(p.getDouble("x2"), p.getDouble("y2"));
        ensureNotOnPill(a);
        long duration = Math.max(50, Math.min(5000, p.optLong("duration_ms", 300)));
        Path path = new Path();
        path.moveTo(a[0], a[1]);
        path.lineTo(b[0], b[1]);
        boolean ok = gesture(path, duration);
        if (!ok) throw new RpcError("gesture_cancelled", "系统取消了这次滑动。");
        JSONObject o = new JSONObject();
        o.put("from", new JSONArray().put(Math.round(a[0])).put(Math.round(a[1])));
        o.put("to", new JSONArray().put(Math.round(b[0])).put(Math.round(b[1])));
        return o;
    }

    private JSONObject key(JSONObject p) throws Exception {
        String name = p.getString("name").toLowerCase(Locale.ROOT);
        boolean ok;
        switch (name) {
            case "back": ok = performGlobalAction(GLOBAL_ACTION_BACK); break;
            case "home": ok = performGlobalAction(GLOBAL_ACTION_HOME); break;
            case "recents": ok = performGlobalAction(GLOBAL_ACTION_RECENTS); break;
            case "notifications": ok = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); break;
            case "quick_settings": ok = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); break;
            case "power_dialog": ok = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG); break;
            case "lock_screen": ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); break;
            case "enter": {
                AccessibilityNodeInfo f = focusedEditable();
                if (f == null) throw new RpcError("no_focused_field", "没有获得焦点的输入框，无法发送回车。");
                ok = f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                break;
            }
            default:
                throw new RpcError("invalid_argument", "不支持的按键：" + name);
        }
        if (!ok) throw new RpcError("key_failed", "系统没有执行 " + name + "。");
        JSONObject o = new JSONObject();
        o.put("key", name);
        return o;
    }

    // ---------------------------------------------------------------- 输入

    private AccessibilityNodeInfo focusedEditable() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null) continue;
            AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        return null;
    }

    private static AccessibilityNodeInfo editableDescendant(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 12) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo e = editableDescendant(n.getChild(i), depth + 1);
            if (e != null) return e;
        }
        return null;
    }

    private static String currentText(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) return "";
        return str(n.getText());
    }

    private static boolean setText(AccessibilityNodeInfo n, String s) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    private boolean paste(AccessibilityNodeInfo n, String text, boolean replace) throws InterruptedException {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            cm.setPrimaryClip(ClipData.newPlainText("android-use", text));
            latch.countDown();
        });
        latch.await(2, TimeUnit.SECONDS);
        if (replace) {
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText(n).length());
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    private JSONObject typeText(JSONObject p) throws Exception {
        String text = p.getString("text");
        boolean replace = p.optBoolean("replace", true);
        boolean submit = p.optBoolean("submit", false);
        AccessibilityNodeInfo field = null;
        if (p.has("selector")) {
            Item it = resolveOne(p.getJSONObject("selector"));
            field = editableDescendant(it.node, 0);
            if (field == null) throw new RpcError("not_editable", "匹配到的控件不是输入框。");
            if (!field.isFocused()) {
                Rect r = new Rect();
                field.getBoundsInScreen(r);
                if (overlay != null && overlay.pillAt(r.centerX(), r.centerY()) != null) {
                    // 输入框中心正好压在提示条下面：不点它，改用无障碍动作聚焦
                    field.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } else {
                    Path path = new Path();
                    path.moveTo(r.centerX(), r.centerY());
                    gesture(path, 60);
                }
                Thread.sleep(350);
                field.refresh();
            }
        }
        if (field == null) field = focusedEditable();
        if (field == null) throw new RpcError("no_focused_field", "没有获得焦点的输入框：先点一下输入框再输入。");
        String before = currentText(field);
        String target = replace ? text : before + text;
        String method = "set_text";
        boolean ok = setText(field, target);
        if (!ok) {
            method = "paste";
            ok = paste(field, text, replace);
        }
        if (!ok) throw new RpcError("input_failed", "这个输入框拒绝了写入（set_text 和粘贴都失败）。");
        Thread.sleep(150);
        field.refresh();
        String after = currentText(field);
        JSONObject o = new JSONObject();
        o.put("method", method);
        o.put("characters", text.length());
        if (field.isPassword()) {
            o.put("readback", "<密码已隐藏>");
        } else {
            o.put("readback", clip(after, 600));
            o.put("matches", after.equals(target));
        }
        if (submit) {
            boolean s = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            o.put("submitted", s);
            if (!s) o.put("submit_hint", "这个输入框不支持输入法回车动作，请点界面上的发送 / 搜索按钮。");
        }
        return o;
    }

    // ---------------------------------------------------------------- App

    private JSONObject apps(JSONObject p) throws JSONException {
        String q = p.optString("query", "").toLowerCase(Locale.ROOT);
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(launcher, 0);
        List<JSONObject> rows = new ArrayList<>();
        for (ResolveInfo ri : list) {
            String label = str(ri.loadLabel(pm));
            String pkg = ri.activityInfo.packageName;
            if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q) && !pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            JSONObject o = new JSONObject();
            o.put("label", label);
            o.put("package", pkg);
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                if (pi.versionName != null) o.put("version", pi.versionName);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            rows.add(o);
        }
        Collections.sort(rows, (a, b) -> a.optString("label").compareTo(b.optString("label")));
        JSONArray arr = new JSONArray();
        for (int i = 0; i < rows.size() && i < 300; i++) arr.put(rows.get(i));
        JSONObject o = new JSONObject();
        o.put("count", rows.size());
        o.put("apps", arr);
        return o;
    }

    private JSONObject launch(JSONObject p) throws Exception {
        String pkg = p.optString("package", "");
        PackageManager pm = getPackageManager();
        if (pkg.isEmpty() && p.has("name")) {
            String name = p.getString("name");
            List<String> exact = new ArrayList<>(), partial = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                String label = str(ri.loadLabel(pm));
                if (label.equalsIgnoreCase(name)) exact.add(ri.activityInfo.packageName);
                else if (label.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) partial.add(ri.activityInfo.packageName);
            }
            List<String> pick = !exact.isEmpty() ? exact : partial;
            if (pick.size() != 1) {
                JSONObject d = new JSONObject();
                d.put("candidates", new JSONArray(pick));
                throw new RpcError(pick.isEmpty() ? "not_installed" : "ambiguous_app",
                        pick.isEmpty() ? "桌面上没有叫「" + name + "」的 App。" : "有多个 App 名字含「" + name + "」，请用包名。", d);
            }
            pkg = pick.get(0);
        }
        if (pkg.isEmpty()) throw new RpcError("invalid_argument", "需要 package 或 name。");
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) throw new RpcError("not_installed", "没有安装 " + pkg + "，或它没有桌面入口。");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
        JSONObject o = new JSONObject();
        o.put("package", pkg);
        return o;
    }

    private JSONObject openUrl(JSONObject p) throws Exception {
        String url = p.getString("url");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            throw new RpcError("no_handler", "手机上没有能打开这个链接的 App：" + url);
        }
        JSONObject o = new JSONObject();
        o.put("url", url);
        return o;
    }
}
