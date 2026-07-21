package com.hermes.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hermes.android.ai.AiClient;
import com.hermes.android.ai.AiProviderConfig;
import com.hermes.android.bridge.BridgeFactory;
import com.hermes.android.cron.CronManager;
import com.hermes.android.skill.SkillStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MOV — 客户端壳 Activity。
 * P1-1: Bridge 方法全部拆到 bridge/ 包，本类只保留壳职责。
 */
public class HermesActivity extends AppCompatActivity {

    private static final String TAG = "MOV";
    private static final int PERM_REQUEST = 1001;
    private static final String HISTORY_KEY = "chat_history_json";

    private WebView shell;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final CapabilityExecutor capabilityExecutor = new CapabilityExecutor();
    private AiProviderConfig aiConfig;
    private CronManager cronManager;
    private SkillStore skillStore;
    private StatsCollector statsCollector;
    private StorageManager storageManager;
    private final List<AiClient.Message> chatHistory = new ArrayList<>();
    private final ExecutorService aiExecutor = Executors.newFixedThreadPool(2);

    private volatile boolean roomOpen = false;
    private volatile String pendingFileCallbackId;
    private volatile String pendingFileRoomId;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    // ==================== Public accessors for Bridge classes ====================
    public CapabilityExecutor getCapabilityExecutor() { return capabilityExecutor; }
    public AiProviderConfig getAiConfig() { return aiConfig; }
    public CronManager getCronManager() { return cronManager; }
    public SkillStore getSkillStore() { return skillStore; }
    public StatsCollector getStatsCollector() { return statsCollector; }
    public StorageManager getStorageManager() { return storageManager; }
    public ExecutorService getAiExecutor() { return aiExecutor; }
    public List<AiClient.Message> getChatHistory() { return chatHistory; }
    public void setRoomOpenPublic(boolean open) { roomOpen = open; }

    public void evalJsPublic(String script) {
        uiHandler.post(() -> {
            if (shell != null && !isDestroyed() && !isFinishing()) {
                shell.evaluateJavascript(script, null);
            }
        });
    }

    public void saveChatHistoryPublic() { saveChatHistory(); }

    public void pickFilePublic(String callbackId, String roomId) {
        pendingFileCallbackId = callbackId;
        pendingFileRoomId = roomId;
        uiHandler.post(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });
    }

    // ==================== Lifecycle ====================

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes);

        aiConfig = new AiProviderConfig(this);
        cronManager = new CronManager(this);
        skillStore = new SkillStore(this);
        statsCollector = new StatsCollector(this);
        storageManager = new StorageManager(this);
        capabilityExecutor.init(this);
        statsCollector.onSessionStart();
        statsCollector.tryReport();

        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try { statsCollector.recordCrash(); } catch (Exception ignored) {}
            if (defaultHandler != null) defaultHandler.uncaughtException(thread, ex);
        });

        restoreChatHistory();

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
                            if (roomId != null && !roomId.isEmpty()) {
                                copyFileToRoom(roomId, uri);
                            }
                            String info = getFileInfoJson(uri);
                            evalJsPublic("window._hermesCb('" + cbId + "'," + info + ")");
                            return;
                        }
                    }
                    evalJsPublic("window._hermesCb('" + cbId + "',null)");
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
        // P1-1: 注入聚合 Bridge 替代内部类
        shell.addJavascriptInterface(new BridgeFactory(this), "HermesBridge");
        shell.loadUrl("file:///android_asset/hermes-shell.html");

        requestPermissions();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (roomOpen) {
                    evalJsPublic("if(window.curRoomId!=null){genCounter++;curRoomId=null;" +
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
        shell.post(() -> evalJsPublic(
            "if(window.refreshRuntime)refreshRuntime();"));
    }

    @Override
    protected void onDestroy() {
        if (statsCollector != null) statsCollector.onSessionEnd();
        capabilityExecutor.shutdown();
        aiExecutor.shutdownNow();
        saveChatHistory();
        if (shell != null) shell.destroy();
        super.onDestroy();
    }

    // ==================== 聊天历史持久化 ====================

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

    // ==================== 文件操作 ====================

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
            java.io.File base = new java.io.File(storageManager.getRoomsDir(), roomId);
            base.mkdirs();
            java.io.File target = new java.io.File(base, name);
            try (java.io.InputStream is = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream os = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            }
            Log.i(TAG, "copyFileToRoom: " + name + " -> " + target.getAbsolutePath());
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
}
