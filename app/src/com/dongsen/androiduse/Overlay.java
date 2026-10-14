package com.dongsen.androiduse;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 无障碍覆盖层（不需要悬浮窗权限）：
 * 顶部提示条告诉手机主人"Agent 正在控制"，点一下即暂停远程控制；
 * 每次 Agent 点击时在落点画一个短暂的光圈，方便人看清它点了哪里。
 */
final class Overlay {
    private static final long ACTIVE_WINDOW_MS = 15000;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView pill;
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
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setPadding(dp(12), dp(4), dp(12), dp(4));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(0xE66D28D9);
            t.setBackground(bg);
            t.setOnClickListener(v -> {
                Prefs.setEnabled(service, false);
                Toast.makeText(service, "已暂停 Agent 远程控制，可在 Android Use 里重新打开", Toast.LENGTH_LONG).show();
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(2);
            try {
                wm.addView(t, lp);
                pill = t;
                // 文字在"正在操作 / 待命中"之间切换会改变宽度，每次布局后都重新记下位置
                t.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> updateBounds());
                refresh();
                main.postDelayed(refresher, 1000);
            } catch (RuntimeException ignored) {
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
            pillBounds = null;
        });
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

    void touch() {
        lastActivity = System.currentTimeMillis();
        main.post(this::refresh);
    }

    private void refresh() {
        if (pill == null) return;
        boolean active = System.currentTimeMillis() - lastActivity < ACTIVE_WINDOW_MS;
        pill.setText(active ? "Agent 正在操作 · 点此暂停" : "Android Use 待命中 · 点此关闭");
        pill.setVisibility(captureHidden ? View.INVISIBLE : View.VISIBLE);
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
