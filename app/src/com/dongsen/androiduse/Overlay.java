package com.dongsen.androiduse;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 无障碍覆盖层（不需要悬浮窗权限）：
 * 顶部提示条告诉手机主人"Agent 正在控制"，点一下即暂停远程控制；
 * 每次 Agent 点击时在落点画一个短暂的光圈，方便人看清它点了哪里。
 *
 * 提示条放在状态栏那一条里：那里没有 App 自己的按钮，Agent 正常操作碰不到它；
 * 刘海 / 挖孔屏会避开摄像头位置。全屏页面盖住状态栏时，AgentService 仍会拒绝落在提示条上的手势。
 */
final class Overlay {
    private static final long ACTIVE_WINDOW_MS = 15000;
    private static final String TEXT_ACTIVE = "AI 操作中 · 点此暂停";
    private static final String TEXT_IDLE = "AI 待命 · 点此关闭";

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView pill;
    private WindowManager.LayoutParams pillLp;
    private int pillWidth;
    private String placedFor; // 上次摆放时的屏幕尺寸 + 状态栏高度 + 刘海位置，变了才重新摆
    private volatile Rect pillBounds; // 提示条在屏幕上的位置（屏幕像素），没显示时为 null
    private volatile long lastActivity;
    private volatile boolean captureHidden;
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (pill != null) main.postDelayed(this, 1000);
        }
    };

    Overlay(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, service.getResources().getDisplayMetrics());
    }

    void showPill() {
        main.post(() -> {
            if (pill != null) return;
            TextView t = new TextView(service);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(10), 0, dp(10), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(0xE66D28D9);
            t.setBackground(bg);
            t.setOnClickListener(v -> {
                Prefs.setEnabled(service, false);
                Toast.makeText(service, "已暂停 Agent 远程控制，可在 Android Use 里重新打开", Toast.LENGTH_LONG).show();
            });
            // 两种文字取较宽的那个当固定宽度，切换文字时位置不跳。
            // 用画笔直接量：挂到窗口之前对 TextView setText 会因为没有 LayoutParams 崩溃。
            float textW = Math.max(t.getPaint().measureText(TEXT_ACTIVE), t.getPaint().measureText(TEXT_IDLE));
            int w = (int) Math.ceil(textW) + t.getPaddingLeft() + t.getPaddingRight() + dp(2);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            // 允许摆进刘海所在的那一条，否则系统会把它挤到状态栏下面，正好压住 App 的标题栏
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            pill = t;
            pillLp = lp;
            pillWidth = w;
            place();
            try {
                wm.addView(t, lp);
                // 以实际布局到屏幕上的位置为准
                t.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> updateBounds());
                refresh();
                main.postDelayed(refresher, 1000);
            } catch (RuntimeException e) {
                pill = null;
                pillLp = null;
                pillBounds = null;
            }
        });
    }

    void hidePill() {
        main.post(() -> {
            if (pill == null) return;
            try {
                wm.removeView(pill);
            } catch (RuntimeException ignored) {
            }
            pill = null;
            pillLp = null;
            pillBounds = null;
        });
    }

    /** 状态栏的实际高度：以系统状态栏窗口为准（刘海屏会比标准值高），找不到时用系统尺寸。 */
    private int statusBarHeight(int sw, int sh) {
        try {
            Rect r = new Rect();
            for (AccessibilityWindowInfo win : service.getWindows()) {
                if (win.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                win.getBoundsInScreen(r);
                if (r.top == 0 && r.left == 0 && r.width() >= sw * 9 / 10 && r.height() > 0 && r.height() < sh / 8) return r.height();
            }
        } catch (RuntimeException ignored) {
        }
        int id = service.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? service.getResources().getDimensionPixelSize(id) : 0;
    }

    private Rect topCutout() {
        DisplayManager dm = (DisplayManager) service.getSystemService(AccessibilityService.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayCutout c = d == null ? null : d.getCutout();
        if (c == null) return null;
        Rect r = c.getBoundingRectTop();
        return r == null || r.isEmpty() ? null : r;
    }

    /** 计算提示条的位置和大小；只在主线程调用。 */
    private void place() {
        Rect screen = wm.getMaximumWindowMetrics().getBounds();
        int sw = screen.width();
        int bar = statusBarHeight(sw, screen.height());
        Rect cut = topCutout();
        String key = sw + "x" + screen.height() + "/" + bar + "/" + cut;
        if (key.equals(placedFor)) return;
        int h, y;
        if (bar >= dp(20)) {
            // 在状态栏里垂直居中；刘海屏状态栏很高时也不撑得太胖
            h = Math.min(bar - dp(4), dp(26));
            y = (bar - h) / 2;
        } else {
            h = dp(24);
            y = dp(2);
        }
        int w = Math.min(pillWidth, sw - dp(8));
        int x = (sw - w) / 2;
        if (cut != null && cut.left < x + w && cut.right > x && cut.top < y + h) {
            int gap = dp(6);
            if (cut.right + gap + w <= sw - dp(4)) x = cut.right + gap;
            else if (cut.left - gap - w >= dp(4)) x = cut.left - gap - w;
        }
        pillLp.width = w;
        pillLp.height = h;
        pillLp.x = x;
        pillLp.y = y;
        placedFor = key;
        // 先按摆放参数记下位置，布局完成后再以实际位置校正
        pillBounds = new Rect(x, y, x + w, y + h);
    }

    private void updateBounds() {
        if (pill == null || !pill.isAttachedToWindow()) {
            pillBounds = null;
            return;
        }
        int[] loc = new int[2];
        pill.getLocationOnScreen(loc);
        pillBounds = new Rect(loc[0], loc[1], loc[0] + pill.getWidth(), loc[1] + pill.getHeight());
    }

    /**
     * 落点在提示条上时返回提示条的位置，否则返回 null。
     * 提示条在截图里是藏起来的，Agent 看不见它；Agent 的点按落上去会把远程控制暂停掉。
     */
    Rect pillAt(float x, float y) {
        Rect r = pillBounds;
        if (r == null) return null;
        int m = dp(2);
        return x >= r.left - m && x < r.right + m && y >= r.top - m && y < r.bottom + m ? r : null;
    }

    /** 提示条当前位置（屏幕像素），没显示时为 null。 */
    Rect pillRect() {
        Rect r = pillBounds;
        return r == null ? null : new Rect(r);
    }

    void touch() {
        lastActivity = System.currentTimeMillis();
        main.post(this::refresh);
    }

    private void refresh() {
        if (pill == null) return;
        boolean active = System.currentTimeMillis() - lastActivity < ACTIVE_WINDOW_MS;
        pill.setText(active ? TEXT_ACTIVE : TEXT_IDLE);
        pill.setVisibility(captureHidden ? View.INVISIBLE : View.VISIBLE);
        // 旋转屏幕、状态栏变化时跟着挪
        String before = placedFor;
        place();
        if (!placedFor.equals(before)) {
            try {
                wm.updateViewLayout(pill, pillLp);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** 截屏前把提示条藏起来，免得它挡住 Agent 要看的内容。 */
    void setCaptureHidden(boolean hidden) {
        captureHidden = hidden;
        main.post(this::refresh);
    }

    void ripple(final float x, final float y) {
        main.post(() -> {
            final View dot = new View(service);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(0x556D28D9);
            d.setStroke(dp(2), 0xFFFFFFFF);
            dot.setBackground(d);
            int size = dp(44);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            // 坐标按整块屏幕算（含状态栏和刘海区域），与手势坐标一致
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.x = Math.round(x) - size / 2;
            lp.y = Math.round(y) - size / 2;
            try {
                wm.addView(dot, lp);
            } catch (RuntimeException e) {
                return;
            }
            dot.animate().alpha(0f).scaleX(1.6f).scaleY(1.6f).setDuration(380).start();
            main.postDelayed(() -> {
                try {
                    wm.removeView(dot);
                } catch (RuntimeException ignored) {
                }
            }, 420);
        });
    }
}
