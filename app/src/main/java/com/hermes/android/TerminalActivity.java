package com.hermes.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.hermes.android.linux.RootfsManager;
import com.hermes.android.linux.TerminalEnv;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/**
 * M3 交互终端 — 原生 TerminalView (termux-app 移植) 跑 proot Ubuntu shell。
 *
 * 会话: proot -0 --kill-on-exit --link2symlink -r rootfs ... /usr/bin/bash -l
 * (参数形态与 M1 ProotRunner 一致, 见 TerminalEnv; pty 由 terminal-emulator JNI 提供)
 * rootfs 未 READY → 引导文本, 不开会话。
 */
public class TerminalActivity extends AppCompatActivity
        implements TerminalSessionClient, TerminalViewClient {

    private static final String TAG = "TerminalActivity";

    private TerminalView terminalView;
    private TerminalSession session;
    private TextView ctrlKey, altKey;
    private boolean ctrlDown = false, altDown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);

        if (!RootfsManager.isReady(this)) {
            setContentView(buildGuideView());
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        /* 键盘弹出时按键行保持可见: adjustResize 需要布局 fitsSystemWindows 才生效 */
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.setFitsSystemWindows(true);

        terminalView = new TerminalView(this, null);
        terminalView.setTextSize(28);
        /* TerminalView 默认不可 focus (termux 在 XML 里声明 focusable),
           不设的话 requestFocus 永远失败, 键盘/IME 输入全被按键行按钮截获 */
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        root.addView(terminalView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(buildExtraKeys());
        setContentView(root);

        TerminalEnv.Launch launch = TerminalEnv.build(this);
        session = new TerminalSession(launch.shellPath, "/root",
                launch.args, launch.env, null, this);
        terminalView.attachSession(session);
        terminalView.setTerminalViewClient(this);
        terminalView.requestFocus();
    }

    /** rootfs 未就绪的引导页 */
    private View buildGuideView() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setPadding(48, 48, 48, 48);
        TextView t = new TextView(this);
        t.setText("Linux 环境未就绪\n\n请先到 设置 → Linux 环境 安装 (约 1 分钟), 再回来打开终端。");
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        v.addView(t);
        Button b = new Button(this);
        b.setText("返回");
        b.setOnClickListener(x -> finish());
        v.addView(b);
        v.setBackgroundColor(Color.BLACK);
        return v;
    }

    /* ── 虚拟按键行: ESC CTRL ALT TAB + 方向键 (termux ExtraKeysView 简化版) ── */

    private View buildExtraKeys() {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setBackgroundColor(0xFF1A1A1A);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(8, 4, 8, 4);

        row.addView(keyButton("ESC", v -> sendBytes(new byte[]{0x1B})));
        ctrlKey = toggleButton("CTRL", () -> {
            ctrlDown = !ctrlDown;
            ctrlKey.setTextColor(ctrlDown ? 0xFF7FD4FF : 0xFFCCCCCC);
        });
        row.addView(ctrlKey);
        altKey = toggleButton("ALT", () -> {
            altDown = !altDown;
            altKey.setTextColor(altDown ? 0xFF7FD4FF : 0xFFCCCCCC);
        });
        row.addView(altKey);
        row.addView(keyButton("TAB", v -> sendBytes(new byte[]{0x09})));
        row.addView(keyButton("←", v -> sendBytes(new byte[]{0x1B, '[', 'D'})));
        row.addView(keyButton("↓", v -> sendBytes(new byte[]{0x1B, '[', 'B'})));
        row.addView(keyButton("↑", v -> sendBytes(new byte[]{0x1B, '[', 'A'})));
        row.addView(keyButton("→", v -> sendBytes(new byte[]{0x1B, '[', 'C'})));

        sv.addView(row);
        return sv;
    }

    private Button keyButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xFFCCCCCC);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(24, 0, 24, 0);
        b.setBackgroundColor(0xFF2A2A2A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private TextView toggleButton(String label, Runnable onToggle) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFCCCCCC);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(24, 0, 24, 0);
        t.setBackgroundColor(0xFF2A2A2A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.rightMargin = dp(6);
        lp.gravity = Gravity.CENTER_VERTICAL;
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> onToggle.run());
        return t;
    }

    private void sendBytes(byte[] bytes) {
        if (session != null) {
            session.write(bytes, 0, bytes.length);
            terminalView.post(terminalView::requestFocus);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.finishIfRunning();
        super.onDestroy();
    }

    /* ── TerminalSessionClient ── */

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (terminalView != null) terminalView.onScreenUpdated();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {}

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        runOnUiThread(() -> Toast.makeText(this, "会话已结束", Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession s, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0 && s != null) {
            String text = clip.getItemAt(0).coerceToText(this).toString();
            byte[] bytes = text.getBytes();
            s.write(bytes, 0, bytes.length);
        }
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {}

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {}

    @Override
    public void onTerminalCursorStateChange(boolean state) {}

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {}

    @Override
    public Integer getTerminalCursorStyle() { return null; }

    /* ── TerminalViewClient ── */

    @Override
    public float onScale(float scale) { return 0; }

    @Override
    public void onSingleTapUp(MotionEvent e) {}

    @Override
    public boolean shouldBackButtonBeMappedToEscape() { return false; }

    @Override
    public boolean shouldEnforceCharBasedInput() { return false; }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() { return false; }

    @Override
    public boolean isTerminalViewSelected() { return false; }

    @Override
    public void copyModeChanged(boolean copyMode) {}

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }

    @Override
    public boolean onLongPress(MotionEvent event) { return false; }

    @Override
    public boolean readControlKey() { return ctrlDown; }

    @Override
    public boolean readAltKey() { return altDown; }

    @Override
    public boolean readShiftKey() { return false; }

    @Override
    public boolean readFnKey() { return false; }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {}

    /* ── 日志 (两接口共用) ── */

    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "error", e); }
}
