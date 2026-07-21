package com.hermes.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.UUID;

/**
 * 匿名使用统计。默认关闭，用户主动开启。
 * 只传统计数字，不传聊天内容/文件名/Key/设备信息。
 * 本地聚合，每 24h 上报一次。
 */
public class StatsCollector {

    private static final String TAG = "StatsCollector";
    private static final String PREFS = "mov_stats";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_LAST_REPORT = "last_report_at";

    // TODO: 注册 Supabase 后填入
    private static final String REPORT_ENDPOINT = "";
    private static final String SUPABASE_ANON_KEY = "";

    private int aiCalls = 0;
    private long aiTotalMs = 0;
    private long aiMaxMs = 0;
    private int aiErrors = 0;
    private int sessionCount = 0;
    private long sessionStartMs = 0;
    private long sessionTotalMs = 0;
    private int cronExecuted = 0;
    private int crashCount = 0;
    private String aiProvider = "";
    private String aiModel = "";

    private final SharedPreferences prefs;
    private final String installId;

    public StatsCollector(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_INSTALL_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_INSTALL_ID, id).apply();
        }
        this.installId = id;
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public void recordAiCall(String provider, String model, long latencyMs, boolean success) {
        aiProvider = provider;
        aiModel = model;
        aiCalls++;
        aiTotalMs += latencyMs;
        if (latencyMs > aiMaxMs) aiMaxMs = latencyMs;
        if (!success) aiErrors++;
    }

    public void recordCronExecuted() { cronExecuted++; }

    public void recordCrash() { crashCount++; }

    public void onSessionStart() {
        sessionCount++;
        sessionStartMs = System.currentTimeMillis();
    }

    public void onSessionEnd() {
        if (sessionStartMs > 0) {
            sessionTotalMs += (System.currentTimeMillis() - sessionStartMs);
            sessionStartMs = 0;
        }
    }

    public boolean shouldReport() {
        if (REPORT_ENDPOINT.isEmpty()) return false;
        long last = prefs.getLong(KEY_LAST_REPORT, 0);
        return (System.currentTimeMillis() - last) > 24 * 60 * 60 * 1000;
    }

    public JSONObject buildReport() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", "3.1");
            o.put("install", installId);
            o.put("day", new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US).format(new java.util.Date()));

            JSONObject aiObj = new JSONObject();
            aiObj.put("provider", aiProvider);
            aiObj.put("model", aiModel);
            aiObj.put("calls", aiCalls);
            aiObj.put("avgLatencyMs", aiCalls > 0 ? aiTotalMs / aiCalls : 0);
            aiObj.put("maxLatencyMs", aiMaxMs);
            aiObj.put("errors", aiErrors);
            o.put("ai", aiObj);

            JSONObject sess = new JSONObject();
            sess.put("count", sessionCount);
            sess.put("totalMinutes", sessionTotalMs / 60000);
            o.put("sessions", sess);

            o.put("cronExecuted", cronExecuted);
            o.put("crashes", crashCount);

            resetCounters();
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void resetCounters() {
        aiCalls = 0; aiTotalMs = 0; aiMaxMs = 0; aiErrors = 0;
        cronExecuted = 0; crashCount = 0;
        sessionCount = 0; sessionTotalMs = 0;
    }

    public void markReported() {
        prefs.edit().putLong(KEY_LAST_REPORT, System.currentTimeMillis()).apply();
    }

    /** 用户预览即将上报的 JSON */
    public String getPreviewJson() {
        return buildReport().toString();
    }

    /** 尝试上报 (后台线程, 静默失败) */
    public void tryReport() {
        if (!isEnabled() || !shouldReport()) return;
        new Thread(() -> {
            try {
                JSONObject report = buildReport();
                java.net.URL url = new java.net.URL(REPORT_ENDPOINT);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", SUPABASE_ANON_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.getOutputStream().write(
                        report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    markReported();
                    Log.i(TAG, "stats reported");
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "stats report failed: " + e.getMessage());
            }
        }).start();
    }
}
