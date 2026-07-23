package com.movgen.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * WebView 壳 — 全屏加载 assets/app.html (MOV 打包器注入的房间产出 HTML)。
 * 零 androidx 依赖, 纯 framework API, 保证模板 APK 最小化。
 */
public class MainActivity extends Activity {

    private WebView web;

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

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);   /* file:///android_asset 在 false 下仍可加载 */
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
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
        });
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/app.html");
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
