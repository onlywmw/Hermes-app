package com.hermes.android.linux;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * RootfsManager — 内嵌 Ubuntu 24.04 rootfs 的安装/状态/卸载。
 *
 * 目录布局 (getFilesDir()):
 *   linux/rootfs/             Ubuntu rootfs (proot -r 的目标)
 *   linux/ubuntu-base.tar.gz  下载暂存包
 *   linux/state.json          持久化状态 {"state":"READY","msg":""}
 *   linux-exchange/           guest /exchange 挂载点 (房间 ⇆ Linux 文件交换)
 *
 * 状态机: NOT_INSTALLED → DOWNLOADING(进度%) → EXTRACTING → BOOTSTRAPPING → READY
 *         任意一步失败 → ERROR(msg)
 */
public class RootfsManager {

    public enum State { NOT_INSTALLED, DOWNLOADING, EXTRACTING, BOOTSTRAPPING, READY, ERROR }

    private static final String TAG = "RootfsManager";

    public interface Listener {
        void onState(State state, int progress, String msg);
    }

    private static final String ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz";
    /** 本地包优先: 用户已 adb push 到共享存储时免下载 */
    private static final File LOCAL_PACKAGE = new File("/sdcard/MOV/ubuntu-base.tar.gz");

    private final Context ctx;
    private final File linuxDir;
    private volatile State state = State.NOT_INSTALLED;
    private volatile int progress = 0;
    private volatile String errorMsg = "";
    private volatile Listener listener;

    public RootfsManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.linuxDir = new File(ctx.getFilesDir(), "linux");
        loadState();
    }

    public File rootfsDir() { return new File(linuxDir, "rootfs"); }
    public File exchangeDir() { return new File(ctx.getFilesDir(), "linux-exchange"); }
    private File stagingPkg() { return new File(linuxDir, "ubuntu-base.tar.gz"); }
    private File stateFile() { return new File(linuxDir, "state.json"); }

    public State getState() { return state; }
    public int getProgress() { return progress; }
    public String getErrorMsg() { return errorMsg; }
    public void setListener(Listener l) { listener = l; }

    /** READY 判定: 持久化状态 + rootfs 关键文件仍在 (防用户清数据后状态撒谎) */
    public boolean isReady() {
        return state == State.READY && new File(rootfsDir(), "usr/bin/env").isFile();
    }

    public static boolean isReady(Context ctx) {
        return new RootfsManager(ctx).isReady();
    }

    /** 磁盘占用 (linux + linux-exchange, 字节) */
    public long diskUsage() {
        return dirSize(linuxDir) + dirSize(exchangeDir());
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += dirSize(k);
        return sum;
    }

    // ==================== 安装 ====================

    /** 后台线程跑完整安装流程; 重入安全 (进行中再调直接忽略) */
    public synchronized void install() {
        if (state == State.DOWNLOADING || state == State.EXTRACTING
                || state == State.BOOTSTRAPPING) return;
        new Thread(this::doInstall, "rootfs-install").start();
    }

    private void doInstall() {
        try {
            exchangeDir().mkdirs();
            if (!Proot.present(ctx)) {
                setState(State.ERROR, 0, "proot 未随包安装 (非 arm64 设备?)");
                return;
            }
            /* ① 取包: 本地优先, 否则下载 */
            File pkg = stagingPkg();
            if (!obtainPackage(pkg)) return; // 内部已置 ERROR
            /* ② 解压前必须彻底清空 rootfs: 带残渣解压 GNU tar 会报
               "Cannot open: File exists" (旧 rootfs 可能来自手动装包/上次失败的安装) */
            setState(State.EXTRACTING, 0, "");
            File rootfs = rootfsDir();
            String clearErr = clearDirectory(rootfs);
            if (clearErr != null) {
                setState(State.ERROR, 0, "清空旧 rootfs 失败: " + clearErr);
                return;
            }
            rootfs.mkdirs();
            ProotRunner.ExecResult untar = ProotRunner.runRaw(ctx, Arrays.asList(
                    Proot.binary(ctx).getAbsolutePath(),
                    "-0", "--kill-on-exit", "--link2symlink",
                    Proot.tarBinary(ctx).getAbsolutePath(),
                    "-I", Proot.gzipBinary(ctx).getAbsolutePath(),
                    "-xf", pkg.getAbsolutePath(),
                    "-C", rootfs.getAbsolutePath()), 300);
            if (untar.timeout || untar.exitCode != 0) {
                setState(State.ERROR, 0, "解压失败 exit=" + untar.exitCode + " " + tail(untar.stderr));
                return;
            }
            pkg.delete(); // 30MB 暂存包不留
            /* ③ 初始化: DNS + apt sandbox 绕行 (proot 下 apt 签名验证必败的固定药方) */
            writeFile(new File(rootfs, "etc/resolv.conf"),
                    "nameserver 8.8.8.8\nnameserver 223.5.5.5\n");
            writeFile(new File(rootfs, "etc/apt/apt.conf.d/99mov-proot"),
                    "APT::Sandbox::User \"root\";\n");
            /* ③.5 预装包检测: 预置 rootfs (重打包时 --hard-dereference 消除硬链接,
               shell 域已装好 python3/git) 直接 READY, 跳过 in-app apt —
               app 域 dpkg 的 status-old 硬链接无法被静态 proot 模拟, 装包必败 */
            ProotRunner.ExecResult pre = ProotRunner.exec(ctx, "python3 --version", 60);
            Log.i(TAG, "预装检测 exit=" + pre.exitCode + " out=" + tail(pre.stdout));
            if (pre.exitCode == 0 && pre.stdout.contains("Python")) {
                setState(State.READY, 100, "");
                return;
            }
            /* ④ 首启装包 (vanilla Ubuntu Base 包的兜底路径); 个别 postinst dpkg error
               属正常, 以 python3 --version 判成功;
               update 与 install 分开跑 (proot ptrace 下 dpkg 很慢, 合并跑 600s 不够) */
            setState(State.BOOTSTRAPPING, 0, "");
            ProotRunner.ExecResult upd = ProotRunner.exec(ctx, "apt-get update", 300);
            if (upd.timeout) {
                setState(State.ERROR, 0, "apt-get update 超时, 检查网络后重装");
                return;
            }
            Log.i(TAG, "apt-get update exit=" + upd.exitCode + " tail=" + tail(upd.stderr));
            ProotRunner.ExecResult boot = ProotRunner.exec(ctx,
                    "apt-get install -y python3 python3-venv git", 600);
            Log.i(TAG, "apt-get install exit=" + boot.exitCode + " timeout=" + boot.timeout
                    + " tail=" + tail(boot.stdout + boot.stderr));
            if (boot.timeout) {
                /* proot ptrace 下 dpkg 很慢, 平板首次装 ~100 个包可能超 10 分钟;
                   dpkg 可断点续跑: 先 configure -a 收拾残局再重试一轮 */
                Log.i(TAG, "bootstrap 首轮超时, 续跑第二轮");
                ProotRunner.ExecResult resume = ProotRunner.exec(ctx,
                        "dpkg --configure -a && apt-get install -y python3 python3-venv git", 600);
                Log.i(TAG, "bootstrap 二轮 exit=" + resume.exitCode + " timeout=" + resume.timeout);
                if (resume.timeout) {
                    setState(State.ERROR, 0, "装包超时 (>20 分钟), 检查网络后重装");
                    return;
                }
            }
            ProotRunner.ExecResult check = ProotRunner.exec(ctx, "python3 --version", 30);
            if (check.exitCode == 0 && check.stdout.contains("Python")) {
                setState(State.READY, 100, "");
            } else {
                setState(State.ERROR, 0, "python3 校验失败: " + tail(boot.stdout + boot.stderr));
            }
        } catch (Exception e) {
            setState(State.ERROR, 0, e.getMessage() != null ? e.getMessage() : "安装异常");
        }
    }

    /** 本地包优先 → 下载; 成功返回 true, 失败内部置 ERROR 返回 false */
    private boolean obtainPackage(File pkg) {
        try {
            if (LOCAL_PACKAGE.isFile() && Environment.isExternalStorageManager()) {
                setState(State.DOWNLOADING, 0, "");
                copyWithProgress(LOCAL_PACKAGE, pkg);
                return true;
            }
        } catch (Exception e) {
            /* 本地包坏了就删掉暂存继续走下载 */
            pkg.delete();
        }
        setState(State.DOWNLOADING, 0, "");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ROOTFS_URL).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) {
                setState(State.ERROR, 0, "下载失败 HTTP " + conn.getResponseCode());
                return false;
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(pkg)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    got += n;
                    if (total > 0) setState(State.DOWNLOADING, (int) (got * 100 / total), "");
                }
            }
            return true;
        } catch (Exception e) {
            pkg.delete();
            setState(State.ERROR, 0, "下载失败: "
                    + (e.getMessage() != null ? e.getMessage() : "网络异常"));
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void copyWithProgress(File src, File dst) throws Exception {
        long total = src.length();
        try (InputStream in = Files.newInputStream(src.toPath());
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                got += n;
                if (total > 0) setState(State.DOWNLOADING, (int) (got * 100 / total), "");
            }
        }
    }

    // ==================== 卸载 ====================

    public synchronized void uninstall() {
        deleteRecursive(rootfsDir());
        File pkg = stagingPkg();
        if (pkg.exists()) pkg.delete();
        File ex = exchangeDir();
        File[] kids = ex.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        setState(State.NOT_INSTALLED, 0, "");
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    /** 严格清空目录 (整个删掉再重建): 任何删除失败返回描述, 全清成功返回 null */
    static String clearDirectory(File dir) {
        if (dir == null || !dir.exists()) return null;
        String err = clearRecursive(dir);
        if (err != null) return err;
        if (!dir.mkdirs() && !dir.isDirectory()) return "重建失败: " + dir.getAbsolutePath();
        /* 复核: 目录必须已空 (listFiles 为 null 也视为失败, 防带残渣解压) */
        String[] left = dir.list();
        if (left == null) return "无法读取 " + dir.getAbsolutePath();
        if (left.length > 0) return "残留 " + left.length + " 项 (如 " + left[0] + ")";
        return null;
    }

    private static String clearRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    String err = clearRecursive(k);
                    if (err != null) return err;
                }
            }
        }
        if (!f.delete()) return "删除失败: " + f.getAbsolutePath();
        return null;
    }

    // ==================== 状态持久化 ====================

    private void setState(State s, int prog, String msg) {
        state = s;
        progress = prog;
        errorMsg = msg != null ? msg : "";
        Log.i(TAG, "state=" + s + " progress=" + prog
                + (errorMsg.isEmpty() ? "" : " msg=" + errorMsg));
        /* 下载进度刷得太勤, 只有非 DOWNLOADING 或整十进度才落盘 */
        if (s != State.DOWNLOADING || prog % 10 == 0) saveState();
        Listener l = listener;
        if (l != null) {
            try { l.onState(s, progress, errorMsg); } catch (Exception ignored) {}
        }
    }

    private void saveState() {
        try {
            linuxDir.mkdirs();
            JSONObject o = new JSONObject()
                    .put("state", state.name())
                    .put("msg", errorMsg);
            try (FileWriter fw = new FileWriter(stateFile())) {
                fw.write(o.toString());
            }
        } catch (Exception ignored) {}
    }

    private void loadState() {
        try {
            File f = stateFile();
            if (!f.isFile()) return;
            String text = new String(Files.readAllBytes(f.toPath()));
            JSONObject o = new JSONObject(text);
            State s = State.valueOf(o.optString("state", "NOT_INSTALLED"));
            state = normalizeRestored(s);
            errorMsg = o.optString("msg", "");
        } catch (Exception ignored) {}
    }

    /** 进程死在中途的 DOWNLOADING/EXTRACTING/BOOTSTRAPPING 一律回落 NOT_INSTALLED (纯函数, 单测用) */
    static State normalizeRestored(State s) {
        if (s == State.DOWNLOADING || s == State.EXTRACTING || s == State.BOOTSTRAPPING) {
            return State.NOT_INSTALLED;
        }
        return s;
    }

    private static void writeFile(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(content);
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 200 ? "…" + t.substring(t.length() - 200) : t;
    }
}
