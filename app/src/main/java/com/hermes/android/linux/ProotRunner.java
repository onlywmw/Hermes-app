package com.hermes.android.linux;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ProotRunner — 在内嵌 Ubuntu rootfs 里执行一条 shell 命令。
 *
 * 命令行与真机验证过的形态一致: proot -r rootfs -b /dev -b /proc -b /sys
 * [-b linux-exchange:/exchange] [-b /sdcard:/sdcard] -w /root
 * /usr/bin/env PATH=.. HOME=/root DEBIAN_FRONTEND=noninteractive /usr/bin/bash -lc 'cmd'
 * (必须显式注入 PATH/HOME — 继承 Android 的 PATH 会让 guest 内命令全部 not found)
 */
public class ProotRunner {

    public static class ExecResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timeout;

        public ExecResult(int exitCode, String stdout, String stderr, boolean timeout) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timeout = timeout;
        }

        /** agent 回灌格式: exit=N + 输出 */
        public String format() {
            return "exit=" + exitCode + (timeout ? " (超时已杀)" : "") + "\n" + stdout
                    + (stderr.isEmpty() ? "" : "\n<stderr>\n" + stderr);
        }
    }

    public static final int MIN_TIMEOUT = 15;
    public static final int MAX_TIMEOUT = 600;
    public static final int DEFAULT_TIMEOUT = 120;

    public static int clampTimeout(int sec) {
        if (sec <= 0) return DEFAULT_TIMEOUT;
        return Math.max(MIN_TIMEOUT, Math.min(MAX_TIMEOUT, sec));
    }

    /** 输出截断 (纯函数, 单测用): 超 6000 字符保留头 1500 + 尾 4000 并标注原始长度 */
    public static String truncate(String s) {
        if (s == null) return "";
        if (s.length() <= 6000) return s;
        return s.substring(0, 1500)
                + "\n…(输出共 " + s.length() + " 字符, 中段已省略; 大输出请重定向到文件再 grep/tail 分段看)\n"
                + s.substring(s.length() - 4000);
    }

    /** 组装 proot 命令行 (纯函数, 单测用)。-0 = fake root: dpkg 检查 geteuid()==0,
     *  app 域拿不到真 root, 必须伪装 (真机验证: 无 -0 时 dpkg 必报 superuser privilege) */
    public static List<String> buildArgs(String prootPath, String rootfsPath,
                                         String exchangePath, boolean bindSdcard, String cmd) {
        List<String> a = new ArrayList<>();
        a.add(prootPath);
        a.add("-0");
        /* 超时被 destroyForcibly 时, proot 死了但 tracee (dpkg-deb 等) 会孤儿化卡死
           在 pipe_write 并持有 dpkg lock, --kill-on-exit 让 proot 死时清场 */
        a.add("--kill-on-exit");
        /* Android 禁止 app 域创建硬链接 (SELinux app_data_file 无 link 权限, dontaudit 无日志),
           dpkg 写 status 前必 link status→status-old → 无此项必死;
           让 proot 把 link(2) 模拟成 symlink */
        a.add("--link2symlink");
        a.add("-r"); a.add(rootfsPath);
        a.add("-b"); a.add("/dev");
        a.add("-b"); a.add("/proc");
        a.add("-b"); a.add("/sys");
        if (exchangePath != null) { a.add("-b"); a.add(exchangePath + ":/exchange"); }
        if (bindSdcard) { a.add("-b"); a.add("/sdcard:/sdcard"); }
        a.add("-w"); a.add("/root");
        a.add("/usr/bin/env");
        a.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        a.add("HOME=/root");
        a.add("DEBIAN_FRONTEND=noninteractive");
        a.add("/usr/bin/bash");
        a.add("-lc");
        a.add(cmd);
        return a;
    }

    /** guest 内执行: rootfs 未就绪由调用方 (BridgeAi) 先拦, 这里直接跑 */
    public static ExecResult exec(Context ctx, String cmd, int timeoutSec) {
        RootfsManager rm = new RootfsManager(ctx);
        rm.exchangeDir().mkdirs();
        List<String> args = buildArgs(
                Proot.binary(ctx).getAbsolutePath(),
                rm.rootfsDir().getAbsolutePath(),
                rm.exchangeDir().getAbsolutePath(),
                Environment.isExternalStorageManager(),
                cmd);
        return runRaw(ctx, args, timeoutSec);
    }

    /** 原始 proot 命令执行 (RootfsManager 解压等不走 rootfs 的场景也用) */
    public static ExecResult runRaw(Context ctx, List<String> args, int timeoutSec) {
        int timeout = clampTimeout(timeoutSec);
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.environment().put("LD_LIBRARY_PATH", Proot.libDir(ctx));
            /* proot 需要可写临时目录 (f2fs probe / 运行时临时文件),
               指到应用私有 tmp; 不设 PROOT_TMP_DIR 时 apt/dpkg 会报
               "can't create temporary file"。
               注意: 不要设 TMPDIR — 它会泄漏进 guest, 指向宿主路径在 guest 里
               不存在, guest 程序 mktemp 必败 (update-ca-certificates 实踩) */
            File tmp = new File(ctx.getFilesDir(), "linux/tmp");
            tmp.mkdirs();
            pb.environment().put("PROOT_TMP_DIR", tmp.getAbsolutePath());
            /* execve EACCES 回退: 目标文件系统 execve 间歇性拒绝时 proot 改走 loader
               (与静态 proot 同源的 libexec/proot/loader, 以 libproot-loader.so 随包) */
            pb.environment().put("PROOT_LOADER", Proot.loader(ctx).getAbsolutePath());
            /* 注意: 不要设 PROOT_NO_SECCOMP — 静态 proot (green fork) 的硬链接模拟
               只走 seccomp 拦截路径, 关掉后 linkat 直接 ENOSYS */
            p = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread outReader = pump(p.getInputStream(), out);
            Thread errReader = pump(p.getErrorStream(), err);
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                joinQuietly(outReader); joinQuietly(errReader);
                return new ExecResult(-1, truncate(out.toString()),
                        truncate(err.toString()), true);
            }
            joinQuietly(outReader); joinQuietly(errReader);
            return new ExecResult(p.exitValue(), truncate(out.toString()),
                    truncate(err.toString()), false);
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return new ExecResult(-1, "",
                    "exec 异常: " + (e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName()), false);
        }
    }

    private static Thread pump(java.io.InputStream in, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                /* 按字符读而不是按行: 保留原始换行, 也不依赖末行有 \n */
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try { t.join(2000); } catch (InterruptedException ignored) {}
    }
}
