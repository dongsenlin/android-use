package com.dongsen.androiduse;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 一页设置：开无障碍 → 打开远程控制 → 把配对信息给电脑。界面用代码搭，不依赖任何库。 */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView a11yStatus, serverStatus, addressView, tokenView, pairingView;
    private Switch toggle;
    private boolean updatingToggle;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide(); // 页面里已有大标题
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        TextView title = text("Android Use", 26, true);
        col.addView(title);
        TextView sub = text("让你电脑上的 Codex、Claude 等 Agent 通过 Wi‑Fi 看屏幕、点按、滑动、输入，帮你操作这台手机。不需要 ADB，不需要开发者模式，也不用插线。", 14, false);
        sub.setAlpha(0.75f);
        sub.setPadding(0, dp(6), 0, dp(12));
        col.addView(sub);

        LinearLayout c1 = card(col, "① 开启无障碍服务");
        a11yStatus = text("", 15, true);
        c1.addView(a11yStatus);
        c1.addView(hint("设置 → 无障碍 → 已下载的应用 → Android Use → 开启。若开关是灰的（安卓 13 起对非应用商店安装的 App 会限制），先点下面「应用信息」，右上角 ⋮ 选「允许受限制的设置」，再回来开启。"));
        LinearLayout row1 = row(c1);
        row1.addView(button("去开启", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        row1.addView(button("应用信息", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));

        LinearLayout c2 = card(col, "② 允许 Agent 远程控制");
        toggle = new Switch(this);
        toggle.setText("开启后电脑凭配对码即可操作手机");
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        toggle.setOnCheckedChangeListener((b, on) -> {
            if (updatingToggle) return;
            Prefs.setEnabled(this, on);
            handler.postDelayed(this::refresh, 300);
        });
        c2.addView(toggle);
        serverStatus = text("", 14, false);
        serverStatus.setPadding(0, dp(6), 0, 0);
        c2.addView(serverStatus);
        c2.addView(hint("控制期间手机顶部会出现紫色提示条，点一下就暂停。只在信任的 Wi‑Fi 下打开；不用时关掉。"));

        LinearLayout c3 = card(col, "③ 在电脑上配对");
        addressView = text("", 15, false);
        addressView.setTextIsSelectable(true);
        c3.addView(addressView);
        tokenView = text("", 15, false);
        tokenView.setTextIsSelectable(true);
        tokenView.setTypeface(Typeface.MONOSPACE);
        c3.addView(tokenView);
        pairingView = text("", 12, false);
        pairingView.setTextIsSelectable(true);
        pairingView.setTypeface(Typeface.MONOSPACE);
        pairingView.setAlpha(0.7f);
        pairingView.setPadding(0, dp(6), 0, 0);
        c3.addView(pairingView);
        c3.addView(hint("把这串配对信息发给电脑上的 Agent，例如对 Codex / Claude 说：「用 android-use-setup 配对这台手机：androiduse://…」。配对码相当于遥控器钥匙，不要发给别人。"));
        LinearLayout row3 = row(c3);
        row3.addView(button("复制配对信息", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Android Use", Prefs.pairing(this)));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }));
        row3.addView(button("换一个配对码", v -> {
            Prefs.regenerateToken(this);
            Toast.makeText(this, "已更换，旧配对码立即失效", Toast.LENGTH_SHORT).show();
            refresh();
        }));

        LinearLayout c4 = card(col, "④ 防止被系统杀后台（可选）");
        c4.addView(hint("小米、华为、OPPO、vivo 等系统会清理后台服务，导致无障碍自动关闭。建议把 Android Use 设为「电池不受限制 / 允许自启动」，并在最近任务里锁定。"));
        LinearLayout row4 = row(c4);
        row4.addView(button("电池优化设置", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));

        TextView ver = text("版本 " + AgentService.VERSION, 12, false);
        ver.setAlpha(0.5f);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(18), 0, 0);
        col.addView(ver);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private boolean a11yEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(this, AgentService.class);
        for (String s : enabled.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    private void refresh() {
        boolean enabled = a11yEnabled();
        AgentService svc = AgentService.instance;
        if (svc != null) {
            a11yStatus.setText("✅ 无障碍服务已开启");
        } else if (enabled) {
            a11yStatus.setText("⏳ 已开启，服务启动中…（若一直如此，关掉再开一次）");
        } else {
            a11yStatus.setText("❌ 还没开启");
        }
        updatingToggle = true;
        toggle.setChecked(Prefs.enabled(this));
        updatingToggle = false;
        String status;
        if (!Prefs.enabled(this)) status = "已关闭：电脑无法连接这台手机。";
        else if (svc == null) status = "等待无障碍服务开启…";
        else if (svc.serverError() != null) status = "⚠️ " + svc.serverError();
        else if (svc.serverRunning()) status = "✅ 正在监听端口 " + Prefs.port(this) + "，等待 Agent 连接。";
        else status = "启动中…";
        serverStatus.setText(status);
        List<String> ips = Prefs.localAddresses();
        addressView.setText("地址：" + (ips.isEmpty() ? "未连接 Wi‑Fi" : TextUtils.join(" / ", ips)) + "  端口：" + Prefs.port(this));
        tokenView.setText("配对码：" + Prefs.token(this));
        pairingView.setText(Prefs.pairing(this));
    }

    // ------------------------------------------------------------ 小部件

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 13, false);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x146D28D9);
        bg.setStroke(dp(1), 0x336D28D9);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        TextView h = text(heading, 17, true);
        h.setPadding(0, 0, 0, dp(6));
        c.addView(h);
        return c;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, 0);
        parent.addView(r);
        return r;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }
}
