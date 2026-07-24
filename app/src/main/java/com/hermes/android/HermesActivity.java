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
    private static final String HISTORY_KEY = "chat_history_json";
    /** P0: 文件选择器拷贝上限 (与 BridgeValidator 5MB 一致) */
    private static final long MAX_COPY_BYTES = 5L * 1024 * 1024;

    private WebView shell;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final CapabilityExecutor capabilityExecutor = new CapabilityExecutor();
    private AiProviderConfig aiConfig;
    private SkillStore skillStore;
    private com.hermes.android.model.ModelRegistry modelRegistry;
    private StorageManager storageManager;
    private final List<AiClient.Message> chatHistory = new ArrayList<>();

    /** P0-1: 后台线程池 (AI / Council 异步调用) */
    private final ExecutorService aiExecutor = Executors.newFixedThreadPool(2);

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

        /* 状态栏 + 导航栏融入暗色主题 */
        getWindow().setStatusBarColor(0xFF0f172a);
        getWindow().setNavigationBarColor(0xFF0f172a);

        aiConfig = new AiProviderConfig(this);
        modelRegistry = com.hermes.android.model.ModelRegistry.getInstance(this);
        storageManager = new StorageManager(this);
        capabilityExecutor.init(this);
        skillStore = new SkillStore(this);

        // 全局未捕获异常 → 交还默认处理器
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
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
        // P0: 关闭文件/content 访问，防 WebView 越权读本地文件
        // (file:///android_asset 加载在 allowFileAccess=false 下仍可用)
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // 防升级安装后 WebView 复用旧版 JS/CSS 缓存导致前后端文件版本错位 (assets 本地加载, 禁缓存无性能损失)
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        shell.setBackgroundColor(0xFFF6F6F7);
        // P0: URL 白名单，仅放行本地 assets
        shell.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return url == null || !url.startsWith("file:///android_asset/");
            }
        });
        shell.setWebChromeClient(new WebChromeClient() {
            /* Fix: WebView 默认不处理 confirm()/prompt() — confirm 恒返回 false、prompt 恒返回 null,
               导致模板使用命名等流程静默失败。用原生对话框承接。 */
            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                                       android.webkit.JsResult result) {
                new androidx.appcompat.app.AlertDialog.Builder(HermesActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      android.webkit.JsPromptResult result) {
                final android.widget.EditText input = new android.widget.EditText(HermesActivity.this);
                input.setText(defaultValue);
                int pad = (int) (16 * getResources().getDisplayMetrics().density);
                input.setPadding(pad, 0, pad, 0);
                new androidx.appcompat.app.AlertDialog.Builder(HermesActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });
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
        // P0-3: 释放 TTS
        capabilityExecutor.shutdown();
        // P0-1: 关闭线程池
        aiExecutor.shutdownNow();
        // P2-13: 保存聊天历史
        saveChatHistory();
        if (shell != null) {
            // P0: destroy 前先从父布局移除并移除 JS 桥，防泄漏与悬空引用
            shell.removeJavascriptInterface("HermesBridge");
            if (shell.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) shell.getParent()).removeView(shell);
            }
            shell.destroy();
            shell = null;
        }
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

    /** Fix 1: 从 URI 复制文件到房间目录 (P0: 文件名消毒 + roomId 校验 + 子线程 + 5MB 上限) */
    private void copyFileToRoom(String roomId, Uri uri) {
        // P0: roomId 与 StorageManager/BridgeValidator 同规则
        if (com.hermes.android.bridge.BridgeValidator.checkRoomId(roomId) != null) {
            Log.w(TAG, "copyFileToRoom: 非法房间ID " + roomId);
            return;
        }
        String name = "unknown";
        try (android.database.Cursor c = getContentResolver().query(
                uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        }
        // P0: DISPLAY_NAME 消毒 — 拒绝路径分隔与 ".."，只保留文件名本体
        if (name == null || name.contains("/") || name.contains("\\")
                || name.contains("..") || name.isEmpty()) {
            Log.w(TAG, "copyFileToRoom: 非法文件名 " + name);
            return;
        }
        final String safeName = name;
        aiExecutor.execute(() -> {
            try {
                java.io.File base = new java.io.File(storageManager.getRoomsDir(), roomId);
                base.mkdirs();
                java.io.File target = new java.io.File(base, safeName).getCanonicalFile();
                String basePath = base.getCanonicalFile().getPath();
                if (!target.getPath().startsWith(basePath + java.io.File.separator)) {
                    Log.w(TAG, "copyFileToRoom: 路径越界 " + safeName);
                    return;
                }
                long total = 0;
                try (java.io.InputStream is = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream os = new java.io.FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        total += n;
                        // P0: 与 BridgeValidator 5MB 上限一致，超限拒绝
                        if (total > MAX_COPY_BYTES) {
                            Log.w(TAG, "copyFileToRoom: 文件过大 (>5MB) " + safeName);
                            target.delete();
                            return;
                        }
                        os.write(buf, 0, n);
                    }
                }
                Log.i(TAG, "copyFileToRoom: " + safeName + " → " + target.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "copyFileToRoom: " + e.getMessage());
            }
        });
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
    public StorageManager getStorageManager() { return storageManager; }
    public SkillStore getSkillStore() { return skillStore; }
    public void saveChatHistoryPublic() { saveChatHistory(); }
    public void setRoomOpenPublic(boolean open) { roomOpen = open; }
    public void pickFilePublic(String cbId, String roomId) {
        runOnUiThread(() -> {
            // Fix: 单槽位 — 已有未完成的选择时直接回错误回调, 不覆盖前一个回调
            if (pendingFileCallbackId != null) {
                try {
                    String errJson = new JSONObject()
                            .put("ok", false).put("error", "已有文件选择操作进行中").toString();
                    evalJs("window._hermesCb('" + cbId + "'," + errJson + ")");
                } catch (Exception ex) {
                    evalJs("window._hermesCb('" + cbId
                            + "',{\"ok\":false,\"error\":\"已有文件选择操作进行中\"})");
                }
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                pendingFileCallbackId = cbId;
                pendingFileRoomId = roomId;
                filePickerLauncher.launch(intent);
            } catch (Exception e) {
                try {
                    /* JSONObject 构造错误 JSON, 防 e.getMessage() 含引号注入 JS 字符串 */
                    String errJson = new JSONObject()
                            .put("ok", false).put("error", e.getMessage()).toString();
                    evalJs("window._hermesCb('" + cbId + "'," + errJson + ")");
                } catch (Exception ex) {
                    evalJs("window._hermesCb('" + cbId
                            + "',{\"ok\":false,\"error\":\"文件选择失败\"})");
                }
            }
        });
    }

}
