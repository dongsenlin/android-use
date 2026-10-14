package com.dongsen.androiduse;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 开关、端口和配对码都存在本 App 私有的 SharedPreferences 里。 */
final class Prefs {
    static final String NAME = "android_use";
    static final String KEY_ENABLED = "server_enabled";
    static final String KEY_TOKEN = "token";
    static final String KEY_PORT = "port";
    static final int DEFAULT_PORT = 8787;
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return get(c).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context c, boolean on) {
        get(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    static int port(Context c) {
        return get(c).getInt(KEY_PORT, DEFAULT_PORT);
    }

    static synchronized String token(Context c) {
        String t = get(c).getString(KEY_TOKEN, null);
        return t != null ? t : regenerateToken(c);
    }

    /** 120 位随机数，base32 编码成 24 个字符。 */
    static synchronized String regenerateToken(Context c) {
        byte[] bytes = new byte[15];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        String t = sb.toString();
        get(c).edit().putString(KEY_TOKEN, t).apply();
        return t;
    }

    /** 本机所有私网 IPv4 地址（Wi‑Fi 通常是 wlan0）。 */
    static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (ni.getName().startsWith("wlan")) out.add(0, ip); else out.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static String pairing(Context c) {
        List<String> ips = localAddresses();
        String host = ips.isEmpty() ? "<手机IP>" : ips.get(0);
        return "androiduse://" + host + ":" + port(c) + "/?token=" + token(c);
    }
}
