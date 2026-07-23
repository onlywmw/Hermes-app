package com.hermes.android;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.hermes.android.bridge.BridgeValidator;

import java.io.File;
import java.net.URI;

/**
 * HtmlViewerActivity — 房间产出物全屏查看器 (发送到桌面 §CONTRACT_STORAGE).
 *
 * 桌面快捷方式/显式 Intent 拉起, extras 带 roomId + 文件相对路径 (相对 files/work/),
 * 从 StorageManager 磁盘布局加载并全屏展示 (游戏可玩: JS + DOM storage 开)。
 *
 * 安全:
 * - roomId 过 BridgeValidator 同规则; path canonical 后必须在 work 目录内 (resolveWorkFile)
 * - 不注册 HermesBridge (纯展示, 无桥)
 * - URL 白名单: 只放行 work 目录内的 file:// 导航, 其余 (http/https/其他 file) 拦截
 */
public class HtmlViewerActivity extends AppCompatActivity {

    public static final String EXTRA_ROOM_ID = "roomId";
    public static final String EXTRA_PATH = "path";

    private static final int BG = Color.parseColor("#0F172A");
    private static final int INK = Color.parseColor("#E2E8F0");
    private static final int INK_DIM = Color.parseColor("#94A3B8");

    private WebView web;
    private File workDir;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 全屏沉浸 (系统栏手势划出) */
        WindowInsetsControllerCompat ctrl =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
        ctrl.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        /* 解析 + 校验参数 */
        String roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        File target = null;
        String errMsg = null;
        if (roomId == null || BridgeValidator.checkRoomId(roomId) != null) {
            errMsg = "非法的房间标识";
        } else if (path == null || BridgeValidator.checkPath(path) != null) {
            errMsg = "非法的文件路径";
        } else {
            StorageManager sm = new StorageManager(this);
            workDir = new File(sm.getBaseDir(), "rooms/" + roomId + "/files/work");
            target = sm.resolveWorkFile(roomId, path);
            if (target == null) errMsg = "文件不存在或已被删除";
        }

        /* UI: 极简标题栏 (文件名 + 返回) + 全屏 WebView */
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(12);
        bar.setPadding(padH, dp(6), padH, dp(6));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(INK);
        back.setTextSize(26);
        back.setPadding(dp(4), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        TextView title = new TextView(this);
        title.setText(path != null ? new File(path).getName() : "");
        title.setTextColor(INK);
        title.setTextSize(13);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);      /* 游戏逻辑需要 */
        s.setDomStorageEnabled(true);      /* 游戏 localStorage (存档/最高分) */
        s.setAllowFileAccess(true);        /* 加载 file:// 目标文件必需 */
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                /* URL 白名单: 仅放行 work 目录内 file:// 导航 (与 HermesActivity 同思路) */
                return !isAllowedUrl(url);
            }
        });
        /* 必须显式设置 WebChromeClient — 否则 WebView 静默吞掉 alert()
           (游戏"游戏结束"提示依赖它); 默认实现即可, 纯展示不注册桥 */
        web.setWebChromeClient(new android.webkit.WebChromeClient());
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (errMsg != null) {
            showError(errMsg);
        } else {
            web.loadUrl("file://" + target.getAbsolutePath());
        }
    }

    /** 仅放行 work 目录内的 file:// (canonical 防越界); http/https/其他 file 一律拦截 */
    private boolean isAllowedUrl(String url) {
        if (url == null || !url.startsWith("file://") || workDir == null) return false;
        try {
            File req = new File(new URI(url)).getCanonicalFile();
            String base = workDir.getCanonicalFile().getPath();
            return req.getPath().equals(base) || req.getPath().startsWith(base + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private void showError(String msg) {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;background:#0F172A;color:#94A3B8;display:flex;"
                + "flex-direction:column;align-items:center;justify-content:center;height:100vh;"
                + "font-family:sans-serif;text-align:center;padding:24px'>"
                + "<div style='font-size:40px;margin-bottom:16px'>⚠</div>"
                + "<div style='font-size:15px;line-height:1.8'>" + escapeHtml(msg) + "</div>"
                + "<div style='font-size:12px;margin-top:12px;color:#64748B'>回房间重新生成或重新发送到桌面</div>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            if (web.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) web.getParent()).removeView(web);
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
