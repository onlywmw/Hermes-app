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
    private com.hermes.android.model.ModelRegistry modelRegistry;
    private StorageManager storageManager;
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
        modelRegistry = new com.hermes.android.model.ModelRegistry(this);
        storageManager = new StorageManager(this);
        capabilityExecutor.init(this);
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
        shell.addJavascriptInterface(new com.hermes.android.bridge.BridgeFactory(this), "HermesBridge");
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

    /** Bridge 子模块用的公开入口 */
    public void evalJsPublic(String script) {
        evalJs(script);
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
     * Bridge 子模块 getter (供 BridgeFactory 使用)
     * ================================================================ */
    public CapabilityExecutor getCapabilityExecutor() { return capabilityExecutor; }
    public AiProviderConfig getAiConfig() { return aiConfig; }
    public com.hermes.android.model.ModelRegistry getModelRegistry() { return modelRegistry; }
    public ExecutorService getAiExecutor() { return aiExecutor; }
    public List<AiClient.Message> getChatHistory() { return chatHistory; }
    public StatsCollector getStatsCollector() { return statsCollector; }
    public StorageManager getStorageManager() { return storageManager; }
    public CronManager getCronManager() { return cronManager; }
    public SkillStore getSkillStore() { return skillStore; }
    public void saveChatHistoryPublic() { saveChatHistory(); }
    public void setRoomOpenPublic(boolean open) { roomOpen = open; }
    public void pickFilePublic(String cbId, String roomId) {
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                pendingFileCallbackId = cbId;
                pendingFileRoomId = roomId;
                filePickerLauncher.launch(intent);
            } catch (Exception e) {
                evalJs("window._hermesCb('" + cbId + "',{\"ok\":false,\"error\":\"" + e.getMessage() + "\"})");
            }
        });
    }

}
