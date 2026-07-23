package com.movgen.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * WebView 壳 — 全屏加载 assets/app.html (MOV 打包器注入的房间产出 HTML)。
 * 零 androidx 依赖, 纯 framework API, 保证模板 APK 最小化。
 *
 * v2 权限桥: 相机/录音(getUserMedia 经 onPermissionRequest 申请并授权) /
 *            通知与震动 (MovShell JS 桥: notify/vibrate)。
 */
public class MainActivity extends Activity {

    private static final int REQ_MEDIA = 41;
    private static final int REQ_NOTIFY = 42;
    private static final String CHANNEL_ID = "mov_shell_default";

    private WebView web;
    private PermissionRequest pendingWebReq;
    private android.media.MediaRecorder rec;
    private String recResult;
    private String pendingNotifyTitle, pendingNotifyText;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 生成应用允许 CDP 调试 (webview_devtools_remote_<pid>) — 本地侧载工具定位, 便于诊断/E2E */
        WebView.setWebContentsDebuggingEnabled(true);

        /* 全屏沉浸 (手势划出系统栏; 零 androidx, 用 framework systemUiVisibility) */
        final android.view.Window win = getWindow();
        win.setStatusBarColor(Color.BLACK);
        win.setNavigationBarColor(Color.BLACK);
        win.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

        createNotificationChannel();

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);   /* file:///android_asset 在 false 下仍可加载 */
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false); /* 相机预览/录音可自动起 */
        web.addJavascriptInterface(new ShellBridge(), "MovShell");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                /* 只放行本地 assets, 其余 (http/https/其他 file) 拦截 */
                return url == null || !url.startsWith("file:///android_asset/");
            }
        });
        /* 必须接管 alert/confirm — 默认弹窗依赖 Activity 主题,
           Theme.NoTitleBar.Fullscreen 下实测不渲染 (JS 线程卡死, 游戏假死)。
           显式用 DeviceDefault 对话框主题构造 AlertDialog。 */
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     final android.webkit.JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this,
                        android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                                       final android.webkit.JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this,
                        android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel,
                                (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            /* getUserMedia 授权闸: WebView 资源请求 → Android 运行时权限 → grant */
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                boolean needCam = false, needMic = false;
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) needCam = true;
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) needMic = true;
                }
                java.util.List<String> need = new java.util.ArrayList<>();
                if (needCam) need.add(android.Manifest.permission.CAMERA);
                if (needMic) need.add(android.Manifest.permission.RECORD_AUDIO);
                if (need.isEmpty()) { request.deny(); return; }
                java.util.List<String> missing = new java.util.ArrayList<>();
                for (String p : need) {
                    if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
                }
                if (missing.isEmpty()) {
                    request.grant(request.getResources());
                } else {
                    /* 持有 PermissionRequest 跨过异步申请, 回调里再 grant/deny */
                    pendingWebReq = request;
                    requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
                }
            }
        });
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/app.html");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0;
        for (int r : grantResults) granted = granted && r == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MEDIA && pendingWebReq != null) {
            if (granted) pendingWebReq.grant(pendingWebReq.getResources());
            else pendingWebReq.deny();
            pendingWebReq = null;
        } else if (requestCode == REQ_NOTIFY && pendingNotifyTitle != null) {
            if (granted) postNotification(pendingNotifyTitle, pendingNotifyText);
            pendingNotifyTitle = null; pendingNotifyText = null;
        }
    }

    // ==================== MovShell JS 桥 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "应用通知", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private void postNotification(String title, String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification n = b.setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true).build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) (System.currentTimeMillis() % 100000), n);
    }

    class ShellBridge {
        /** 发系统通知 (Android 13+ 懒申请 POST_NOTIFICATIONS, 批准后补发);
           返回 "ok" / "no-permission" — JS 侧禁止盲目报成功 (防假性通畅) */
        @JavascriptInterface
        public String notify(String title, String text) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> {
                    pendingNotifyTitle = title; pendingNotifyText = text;
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
                });
                return "no-permission";
            }
            postNotification(title, text);
            return "ok";
        }

        /** 震动; 返回 false = 无马达/未震 (部分平板无震动服务, 必须如实上报) */
        @JavascriptInterface
        public boolean vibrate(long ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return false;
            v.vibrate(Math.max(0, Math.min(ms, 3000)));
            return true;
        }

        /** 能力自检: JS 启动时查一次, 按返回值显示/隐藏硬件入口 */
        @JavascriptInterface
        public boolean hasVibrator() {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            return v != null && v.hasVibrator();
        }

        /** 原生录音 (绕开 MIUI WebView getUserMedia 音频通道 NotReadableError);
           返回 "recording:秒数" 或 "err:原因"; 到点自动停, 用 recordResult() 取结果 */
        @JavascriptInterface
        public String recordAudio(int seconds) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) return "err:no-permission";
            if (rec != null) return "err:busy";
            try {
                final java.io.File out = new java.io.File(getCacheDir(),
                        "rec_" + System.currentTimeMillis() + ".m4a");
                rec = new android.media.MediaRecorder();
                rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
                rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
                rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
                rec.setOutputFile(out.getAbsolutePath());
                rec.prepare();
                rec.start();
                recResult = null;
                int secs = Math.min(Math.max(seconds, 1), 30);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        rec.stop();
                        recResult = out.exists() && out.length() > 44
                                ? "ok:" + out.length() + "字节:" + out.getAbsolutePath()
                                : "err:无数据";
                    } catch (Exception e) {
                        recResult = "err:" + e.getMessage();
                    }
                    try { rec.release(); } catch (Exception ignored) {}
                    rec = null;
                }, secs * 1000L);
                return "recording:" + secs;
            } catch (Exception e) {
                try { if (rec != null) rec.release(); } catch (Exception ignored) {}
                rec = null;
                return "err:" + e.getMessage();
            }
        }

        /** 取录音结果: "ok:字节数:路径" / "err:原因" / null=还在录 */
        @JavascriptInterface
        public String recordResult() {
            return recResult;
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            if (web.getParent() instanceof ViewGroup) {
                ((ViewGroup) web.getParent()).removeView(web);
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
