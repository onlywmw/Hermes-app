package com.hermes.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hermes.android.ai.AiClient;
import com.hermes.android.ai.AiProviderConfig;
import com.hermes.android.council.CouncilClient;
import com.hermes.android.cron.CronManager;
import com.hermes.android.skill.SkillStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hermes v2.0 — 客户端壳 Activity。
 *
 * P0-1: AI 调用异步化 (后台线程 + evaluateJavascript 回调)
 * P0-3: TTS 生命周期管理 (onDestroy shutdown)
 * P2-13: chatHistory 持久化
 * P2-14: getDeviceInfo 缓存
 * P2-17: OnBackPressedCallback 替代 deprecated onBackPressed
 */
public class HermesActivity extends AppCompatActivity {

    private static final String TAG = "MOV";
    private static final int PERM_REQUEST = 1001;
    private static final int MAX_HISTORY = 10;
    private static final String HISTORY_KEY = "chat_history_json";

    private WebView shell;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final IntentParser parser = new IntentParser();
    private final CapabilityExecutor capabilityExecutor = new CapabilityExecutor();
    private AiProviderConfig aiConfig;
    private CronManager cronManager;
    private SkillStore skillStore;
    private StatsCollector statsCollector;
    private final List<AiClient.Message> chatHistory = new ArrayList<>();

    /** P0-1: 后台线程池 (AI / Council 异步调用) */
    private final ExecutorService aiExecutor = Executors.newFixedThreadPool(2);

    /** P2-14: 设备信息缓存 (5s TTL) */
    private volatile String deviceInfoCache;
    private volatile long deviceInfoCacheTime;
    private static final long DEVICE_CACHE_TTL = 5000;

    /** JS 侧当前是否打开某个房间 */
    private volatile boolean roomOpen = false;

    /** P1-8: 文件选择器回调 ID */
    private volatile String pendingFileCallbackId;
    /** Fix 1: 文件选择器目标房间 ID */
    private volatile String pendingFileRoomId;

    /** P1-8: 文件选择器 */
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes);

        aiConfig = new AiProviderConfig(this);
        cronManager = new CronManager(this);
        skillStore = new SkillStore(this);
        statsCollector = new StatsCollector(this);
        statsCollector.onSessionStart();
        statsCollector.tryReport();

        // TELEMETRY: 全局未捕获异常 → 记录崩溃
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try { statsCollector.recordCrash(); } catch (Exception ignored) {}
            if (defaultHandler != null) defaultHandler.uncaughtException(thread, ex);
        });

        // P2-13: 恢复聊天历史
        restoreChatHistory();

        // P1-8: 注册文件选择器
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (pendingFileCallbackId == null) return;
                    String cbId = pendingFileCallbackId;
                    String roomId = pendingFileRoomId;
                    pendingFileCallbackId = null;
                    pendingFileRoomId = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            // Fix 1: 复制到房间目录
                            if (roomId != null && !roomId.isEmpty()) {
                                copyFileToRoom(roomId, uri);
                            }
                            String info = getFileInfoJson(uri);
                            evalJs("window._hermesCb('" + cbId + "'," + info + ")");
                            return;
                        }
                    }
                    evalJs("window._hermesCb('" + cbId + "',null)");
                });

        shell = findViewById(R.id.shell);
        WebSettings s = shell.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setAllowFileAccess(true);

        shell.setBackgroundColor(0xFFF6F6F7);
        shell.setWebViewClient(new WebViewClient());
        shell.setWebChromeClient(new WebChromeClient());
        shell.addJavascriptInterface(new HermesBridge(), "HermesBridge");
        shell.loadUrl("file:///android_asset/hermes-shell.html");

        requestPermissions();

        // P2-17: OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (roomOpen) {
                    evalJs("if(window.curRoomId!=null){genCounter++;curRoomId=null;" +
                            "if(window.HermesBridge)HermesBridge.setRoomOpen('');" +
                            "setTab('chat');showView('view-rooms');renderRooms();}");
                    roomOpen = false;
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        shell.post(() -> evalJs(
            "if(window.refreshRuntime)refreshRuntime();"));
    }

    @Override
    protected void onDestroy() {
        // TELEMETRY: 会话结束
        if (statsCollector != null) statsCollector.onSessionEnd();
        // P0-3: 释放 TTS
        capabilityExecutor.shutdown();
        // P0-1: 关闭线程池
        aiExecutor.shutdownNow();
        // P2-13: 保存聊天历史
        saveChatHistory();
        if (shell != null) shell.destroy();
        super.onDestroy();
    }

    /** 在主线程安全执行 JS（onDestroy 后自动跳过，避免异步回调 NPE） */
    private void evalJs(String script) {
        uiHandler.post(() -> {
            if (shell != null && !isDestroyed() && !isFinishing()) {
                shell.evaluateJavascript(script, null);
            }
        });
    }

    // ==================== P2-13: 聊天历史持久化 ====================

    private void saveChatHistory() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (chatHistory) {
                for (AiClient.Message m : chatHistory) {
                    arr.put(new JSONObject().put("role", m.role).put("content", m.content));
                }
            }
            getPreferences(MODE_PRIVATE).edit()
                    .putString(HISTORY_KEY, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "saveChatHistory: " + e.getMessage());
        }
    }

    private void restoreChatHistory() {
        try {
            String json = getPreferences(MODE_PRIVATE).getString(HISTORY_KEY, null);
            if (json == null) return;
            JSONArray arr = new JSONArray(json);
            synchronized (chatHistory) {
                chatHistory.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    chatHistory.add(new AiClient.Message(
                            o.getString("role"), o.getString("content")));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "restoreChatHistory: " + e.getMessage());
        }
    }

    // ==================== P1-8: 文件信息 ====================

    /** Fix 1: 从 URI 复制文件到房间目录 */
    private void copyFileToRoom(String roomId, Uri uri) {
        try {
            String name = "unknown";
            try (android.database.Cursor c = getContentResolver().query(
                    uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
            }
            java.io.File base = new java.io.File("/sdcard/mov/rooms/" + roomId);
            base.mkdirs();
            java.io.File target = new java.io.File(base, name);
            try (java.io.InputStream is = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream os = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            }
            Log.i(TAG, "copyFileToRoom: " + name + " → " + target.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "copyFileToRoom: " + e.getMessage());
        }
    }

    private String getFileInfoJson(Uri uri) {
        try {
            String name = "unknown";
            long size = 0;
            String mime = getContentResolver().getType(uri);
            try (android.database.Cursor c = getContentResolver().query(
                    uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    int sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (nameIdx >= 0) name = c.getString(nameIdx);
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx);
                }
            }
            return new JSONObject()
                    .put("name", name)
                    .put("size", size)
                    .put("mime", mime != null ? mime : "application/octet-stream")
                    .put("uri", uri.toString())
                    .toString();
        } catch (Exception e) {
            return "null";
        }
    }

    // ==================== 权限 ====================

    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms = {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE,
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST) {
            int granted = 0;
            for (int r : grantResults) if (r == PackageManager.PERMISSION_GRANTED) granted++;
            Log.i(TAG, "permissions granted: " + granted + "/" + grantResults.length);
        }
    }

    /* ================================================================
     * JS 桥
     * ================================================================ */
    public class HermesBridge {

        @JavascriptInterface
        public String parseIntent(String text) {
            try {
                ParsedCommand cmd = parser.parse(text);
                if (cmd != null && !cmd.isError()) {
                    return new JSONObject().put("cmd", cmd.getCapability()).toString();
                }
            } catch (Exception e) {
                Log.w(TAG, "parseIntent: " + e.getMessage());
            }
            return "{}";
        }

        @JavascriptInterface
        public String execCommand(String text) {
            long t0 = System.currentTimeMillis();
            try {
                ParsedCommand cmd = parser.parse(text);
                if (cmd == null) return "⚠ 无法识别指令: " + text;
                if (cmd.isError()) return "❌ " + cmd.getError();
                CommandResult result = capabilityExecutor.execute(HermesActivity.this, cmd);
                Log.i(TAG, "cmd [" + text + "] " + (result.isSuccess() ? "OK" : "FAIL")
                        + " in " + (System.currentTimeMillis() - t0) + "ms");
                return (result.isSuccess() ? "✅ " : "❌ ") + result.getMessage();
            } catch (SecurityException e) {
                return "❌ 权限不足: " + e.getMessage() + " — 请在系统设置中授权 Hermes";
            } catch (Exception e) {
                return "❌ 执行异常: " + e.getMessage();
            }
        }

        /**
         * P0-1: 异步 AI 调用。
         * JS 侧: B.aiAsync(text, callbackId)
         * 回调: window._hermesCb(callbackId, {ok:true, content:"..."})
         */
        @JavascriptInterface
        public void aiChatAsync(String text, String callbackId) {
            aiExecutor.execute(() -> {
                String resultJson;
                try {
                    if (!aiConfig.isAiEnabled()) {
                        resultJson = "{\"ok\":false,\"content\":\"AI 已关闭, 点右上角 ≡ 可启用。\"}";
                    } else if (!aiConfig.isConfigured()) {
                        resultJson = "{\"ok\":false,\"content\":\"AI 尚未配置 API Key, 点右上角 ≡ 设置后即可畅聊。\"}";
                    } else {
                        AiClient client = new AiClient(aiConfig);
                        List<AiClient.Message> history;
                        synchronized (chatHistory) {
                            history = new ArrayList<>(chatHistory);
                        }
                        long aiT0 = System.currentTimeMillis();
                        AiClient.AiResponse resp = client.chat(text, history);
                        long aiMs = System.currentTimeMillis() - aiT0;
                        if (statsCollector != null) {
                            statsCollector.recordAiCall(aiConfig.getProvider(),
                                    aiConfig.getModel(), aiMs, resp.success);
                        }
                        if (resp.success) {
                            synchronized (chatHistory) {
                                chatHistory.add(new AiClient.Message("user", text));
                                chatHistory.add(new AiClient.Message("assistant", resp.content));
                                while (chatHistory.size() > MAX_HISTORY * 2) chatHistory.remove(0);
                            }
                            saveChatHistory();
                            resultJson = new JSONObject()
                                    .put("ok", true)
                                    .put("content", resp.content).toString();
                        } else {
                            resultJson = new JSONObject()
                                    .put("ok", false)
                                    .put("content", "AI 调用失败: " + resp.content).toString();
                        }
                    }
                } catch (Exception e) {
                    try {
                        resultJson = new JSONObject()
                                .put("ok", false)
                                .put("content", "AI 调用异常: " + e.getMessage()).toString();
                    } catch (Exception ex) {
                        resultJson = "{\"ok\":false,\"content\":\"AI 调用异常\"}";
                    }
                }
                evalJs("window._hermesCb('" + callbackId + "'," + resultJson + ")");
            });
        }

        /**
         * P1-5: 异步 Council 多角色讨论。
         * 同一 AI 后端, 3 个不同 system prompt 模拟 claude/gpt-5/gemini 角色。
         */
        @JavascriptInterface
        public void councilAsync(String topic, String callbackId) {
            aiExecutor.execute(() -> {
                try {
                    CouncilClient council = new CouncilClient(aiConfig);
                    String resultJson = council.discuss(topic);
                    evalJs("window._hermesCb('" + callbackId + "'," + resultJson + ")");
                } catch (Exception e) {
                    evalJs("window._hermesCb('" + callbackId +
                            ",{\"ok\":false,\"error\":\"" + e.getMessage() + "\"})");
                }
            });
        }

        /** 同步 AI (仅设置页测试用, 不在聊天路径使用) */
        @JavascriptInterface
        public String aiChat(String text) {
            if (!aiConfig.isAiEnabled()) return "AI 已关闭, 点右上角 ≡ 可启用。";
            if (!aiConfig.isConfigured()) return "AI 尚未配置 API Key, 点右上角 ≡ 设置后即可畅聊。";
            try {
                AiClient client = new AiClient(aiConfig);
                AiClient.AiResponse resp = client.chat(text, new ArrayList<>());
                if (resp.success) return resp.content;
                return "AI 调用失败: " + resp.content;
            } catch (Exception e) {
                return "AI 调用异常: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String getAiInfo() {
            try {
                return new JSONObject()
                        .put("enabled", aiConfig.isAiEnabled())
                        .put("configured", aiConfig.isConfigured())
                        .put("displayName", aiConfig.getProviderDisplayName().toUpperCase())
                        .put("model", aiConfig.getModel())
                        .put("summary", aiConfig.getStatusSummary())
                        .toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** P2-14: 带缓存的设备信息 */
        @JavascriptInterface
        public String getDeviceInfo() {
            long now = System.currentTimeMillis();
            if (deviceInfoCache != null && (now - deviceInfoCacheTime) < DEVICE_CACHE_TTL) {
                return deviceInfoCache;
            }
            try {
                JSONObject o = new JSONObject();
                o.put("pid", android.os.Process.myPid());
                try {
                    Intent batt = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (batt != null) {
                        int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                        if (scale > 0) o.put("batteryLevel", level * 100 / scale);
                        int st = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        o.put("batteryCharging",
                                st == BatteryManager.BATTERY_STATUS_CHARGING
                                        || st == BatteryManager.BATTERY_STATUS_FULL);
                    }
                } catch (Exception ignored) {}
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    o.put("wifiEnabled", wm.isWifiEnabled());
                    WifiInfo info = wm.getConnectionInfo();
                    if (info != null && info.getSSID() != null) {
                        o.put("wifiSsid", info.getSSID().replace("\"", ""));
                    }
                } catch (Exception ignored) {}
                try {
                    o.put("brightness", Settings.System.getInt(
                            getContentResolver(), Settings.System.SCREEN_BRIGHTNESS));
                } catch (Exception ignored) {}
                String result = o.toString();
                deviceInfoCache = result;
                deviceInfoCacheTime = now;
                return result;
            } catch (Exception e) {
                return "{}";
            }
        }

        // ==================== P1-6: Cron 管理 ====================

        @JavascriptInterface
        public String listCronJobs() {
            return cronManager.listJobsJson();
        }

        @JavascriptInterface
        public String createCronJob(String name, String cronExpr, String command) {
            return cronManager.createJob(name, cronExpr, command);
        }

        @JavascriptInterface
        public String toggleCronJob(String jobId, boolean enabled) {
            return cronManager.toggleJob(jobId, enabled);
        }

        @JavascriptInterface
        public String deleteCronJob(String jobId) {
            return cronManager.deleteJob(jobId);
        }

        // ==================== P1-7: 技能 ====================

        @JavascriptInterface
        public String listSkills() {
            return skillStore.listSkillsJson();
        }

        @JavascriptInterface
        public String recordSkillUse(String skillId) {
            return skillStore.recordUse(skillId);
        }

        @JavascriptInterface
        public String deleteSkill(String skillId) {
            return skillStore.deleteSkill(skillId);
        }

        // ==================== 房间文件操作 ====================

        // ==================== 存储系统 (五种类型) ====================

        @JavascriptInterface
        public String listWorkFiles(String roomId) {
            return StorageManager.listWorkFiles(roomId);
        }

        @JavascriptInterface
        public String saveWorkFile(String roomId, String path, String content, String author) {
            return StorageManager.saveWorkFile(roomId, path, content, author);
        }

        @JavascriptInterface
        public String listVersions(String roomId, String path) {
            return StorageManager.listVersions(roomId, path);
        }

        @JavascriptInterface
        public String restoreVersion(String roomId, String path, String snapshotName) {
            return StorageManager.restoreVersion(roomId, path, snapshotName);
        }

        @JavascriptInterface
        public String listInboxFiles(String roomId) {
            return StorageManager.listInboxFiles(roomId);
        }

        @JavascriptInterface
        public String listArchiveFiles(String roomId) {
            return StorageManager.listArchiveFiles(roomId);
        }

        @JavascriptInterface
        public String writeArchive(String roomId, String source, String content) {
            return StorageManager.writeArchive(roomId, source, content);
        }

        @JavascriptInterface
        public String listTemplates() {
            return StorageManager.listTemplates();
        }

        @JavascriptInterface
        public String saveTemplate(String name, String content) {
            return StorageManager.saveTemplate(name, content);
        }

        @JavascriptInterface
        public String useTemplate(String templateName, String roomId, String targetName) {
            return StorageManager.useTemplate(templateName, roomId, targetName);
        }

        @JavascriptInterface
        public String listNotes() {
            return StorageManager.listNotes();
        }

        @JavascriptInterface
        public String saveNote(String name, String content) {
            return StorageManager.saveNote(name, content);
        }

        @JavascriptInterface
        public String readNote(String name) {
            return StorageManager.readNote(name);
        }

        @JavascriptInterface
        public String deleteNote(String name) {
            return StorageManager.deleteNote(name);
        }

        @JavascriptInterface
        public String initRoomStorage(String roomId) {
            StorageManager.initRoomStorage(roomId);
            return "{\"ok\":true}";
        }

        @JavascriptInterface
        public String getRoomMeta(String roomId) {
            return StorageManager.getRoomMeta(roomId);
        }

        @JavascriptInterface
        public String deleteWorkFile(String roomId, String path) {
            return StorageManager.deleteWorkFile(roomId, path);
        }

        @JavascriptInterface
        public String deleteInboxFile(String roomId, String path) {
            return StorageManager.deleteInboxFile(roomId, path);
        }

        @JavascriptInterface
        public String deleteArchiveFile(String roomId, String path) {
            return StorageManager.deleteArchiveFile(roomId, path);
        }

        @JavascriptInterface
        public String appendChatMessage(String roomId, String messageJson) {
            return StorageManager.appendChatMessage(roomId, messageJson);
        }

        @JavascriptInterface
        public String loadChatMessages(String roomId, String date) {
            return StorageManager.loadChatMessages(roomId, date);
        }

        @JavascriptInterface
        public String writeFile(String roomId, String path, String content) {
            ParsedCommand cmd = new ParsedCommand("file.write")
                    .arg("roomId", roomId).arg("path", path).arg("content", content);
            CommandResult r = capabilityExecutor.execute(HermesActivity.this, cmd);
            try {
                return new JSONObject().put("ok", r.isSuccess()).put("message", r.getMessage()).toString();
            } catch (Exception e) { return "{\"ok\":false}"; }
        }

        @JavascriptInterface
        public String readFile(String roomId, String path) {
            ParsedCommand cmd = new ParsedCommand("file.read")
                    .arg("roomId", roomId).arg("path", path);
            CommandResult r = capabilityExecutor.execute(HermesActivity.this, cmd);
            try {
                JSONObject o = new JSONObject();
                o.put("ok", r.isSuccess());
                if (r.isSuccess()) o.put("content", r.getMessage());
                else o.put("error", r.getMessage());
                return o.toString();
            } catch (Exception e) { return "{\"ok\":false}"; }
        }

        @JavascriptInterface
        public String deleteFile(String roomId, String path) {
            ParsedCommand cmd = new ParsedCommand("file.delete")
                    .arg("roomId", roomId).arg("path", path);
            CommandResult r = capabilityExecutor.execute(HermesActivity.this, cmd);
            try {
                return new JSONObject().put("ok", r.isSuccess()).put("message", r.getMessage()).toString();
            } catch (Exception e) { return "{\"ok\":false}"; }
        }

        @JavascriptInterface
        public String listRoomFiles(String roomId, String subPath) {
            try {
                java.io.File base = new java.io.File("/sdcard/mov/rooms/" + roomId);
                java.io.File dir = (subPath != null && !subPath.isEmpty())
                        ? new java.io.File(base, subPath) : base;
                if (!dir.exists() || !dir.isDirectory()) {
                    return "{\"ok\":true,\"files\":[]}";
                }
                java.io.File[] list = dir.listFiles();
                JSONArray arr = new JSONArray();
                if (list != null) {
                    java.util.Arrays.sort(list, (a, b) -> {
                        if (a.isDirectory() && !b.isDirectory()) return -1;
                        if (!a.isDirectory() && b.isDirectory()) return 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    });
                    for (java.io.File f : list) {
                        if (f.getName().startsWith(".hermes")) continue; // 隐藏元数据
                        arr.put(new JSONObject()
                                .put("name", f.getName())
                                .put("isDir", f.isDirectory())
                                .put("size", f.isFile() ? f.length() : 0));
                    }
                }
                return new JSONObject().put("ok", true).put("files", arr).toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"files\":[]}";
            }
        }

        @JavascriptInterface
        public String initRoom(String roomId, String name, String description, String membersJson) {
            try {
                java.io.File base = new java.io.File("/sdcard/mov/rooms/" + roomId);
                base.mkdirs();
                new java.io.File(base, ".hermes").mkdir();

                String readme = "# " + name + "\n\n" + description + "\n\n## 成员\n\n" + membersJson + "\n";
                try (java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(base, "README.md"))) {
                    fw.write(readme);
                }

                JSONObject config = new JSONObject();
                config.put("name", name);
                config.put("description", description);
                config.put("members", new JSONArray(membersJson));
                config.put("created", System.currentTimeMillis());
                try (java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(base, ".hermes/config.json"))) {
                    fw.write(config.toString(2));
                }

                return "{\"ok\":true}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        // ==================== P1-8: 文件选择 ====================

        @JavascriptInterface
        public void pickFile(String callbackId, String roomId) {
            pendingFileCallbackId = callbackId;
            pendingFileRoomId = roomId;
            uiHandler.post(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                filePickerLauncher.launch(intent);
            });
        }

        // ==================== RUNTIME 真数据 ====================

        /** 进程运行统计: pid / 运行时长 / JVM 内存 / 命令计数 */
        @JavascriptInterface
        public String getRuntimeStats() {
            try {
                Runtime rt = Runtime.getRuntime();
                JSONObject o = new JSONObject();
                o.put("pid", android.os.Process.myPid());
                o.put("uptimeMs", android.os.SystemClock.elapsedRealtime()
                        - android.os.Process.getStartElapsedRealtime());
                o.put("memUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
                o.put("memMaxMb", rt.maxMemory() / 1024 / 1024);
                o.put("cmdCount", CapabilityExecutor.getCmdCount());
                o.put("lastCmdMs", CapabilityExecutor.getLastCmdMs());
                o.put("lastCmdName", CapabilityExecutor.getLastCmdName());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** 权限真实授权状态 (逐个 checkSelfPermission) */
        @JavascriptInterface
        public String getPermissionState() {
            try {
                JSONObject o = new JSONObject();
                String[][] perms = {
                    {"CAMERA", Manifest.permission.CAMERA},
                    {"LOCATION", Manifest.permission.ACCESS_FINE_LOCATION},
                    {"CONTACTS", Manifest.permission.READ_CONTACTS},
                    {"SMS", Manifest.permission.READ_SMS},
                    {"CALL", Manifest.permission.CALL_PHONE},
                    {"NOTIFY", Build.VERSION.SDK_INT >= 33
                            ? Manifest.permission.POST_NOTIFICATIONS : null},
                };
                for (String[] p : perms) {
                    if (p[1] == null) {
                        o.put(p[0], true); // SDK<33 无需通知权限
                        continue;
                    }
                    o.put(p[0], ContextCompat.checkSelfPermission(
                            HermesActivity.this, p[1]) == PackageManager.PERMISSION_GRANTED);
                }
                // 特殊权限: WRITE_SETTINGS
                o.put("SETTINGS", Settings.System.canWrite(HermesActivity.this));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** 桌面小组件真实安装数 */
        @JavascriptInterface
        public String getWidgetInfo() {
            try {
                android.appwidget.AppWidgetManager mgr =
                        android.appwidget.AppWidgetManager.getInstance(HermesActivity.this);
                int[] ids = mgr.getAppWidgetIds(new android.content.ComponentName(
                        HermesActivity.this,
                        com.hermes.android.widget.HermesWidgetProvider.class));
                return new JSONObject().put("count", ids != null ? ids.length : 0).toString();
            } catch (Exception e) {
                return "{\"count\":0}";
            }
        }

        /** 打开系统权限设置页 */
        @JavascriptInterface
        public void openAppSettings() {
            uiHandler.post(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(HermesActivity.this, "无法打开设置页", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ==================== 语言 ====================

        @JavascriptInterface
        public String getLanguage() {
            return aiConfig.getLanguage();
        }

        @JavascriptInterface
        public void setLanguage(String lang) {
            aiConfig.setLanguage(lang);
        }

        // ==================== 基础 ====================

        @JavascriptInterface
        public void toast(final String message) {
            uiHandler.post(() -> Toast.makeText(HermesActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void openAiSettings() {
            uiHandler.post(() ->
                    startActivity(new Intent(HermesActivity.this, HermesSettingsActivity.class)));
        }

        @JavascriptInterface
        public void setRoomOpen(String roomId) {
            roomOpen = roomId != null && !roomId.isEmpty();
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d(TAG, message);
        }
    }
}
