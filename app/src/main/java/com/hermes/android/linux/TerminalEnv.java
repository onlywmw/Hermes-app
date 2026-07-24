package com.hermes.android.linux;

import android.content.Context;
import android.os.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * TerminalEnv — M3 交互终端的 proot 启动参数组装 (纯逻辑可单测)。
 * 与 ProotRunner 同一验证形态, 差异: 交互式 bash -l (非 -lc 'cmd'),
 * 额外注入 TERM; pty 由 terminal-emulator 的 JNI 提供。
 */
public class TerminalEnv {

    public static class Launch {
        public final String shellPath;
        public final String[] args;
        public final String[] env;

        public Launch(String shellPath, String[] args, String[] env) {
            this.shellPath = shellPath;
            this.args = args;
            this.env = env;
        }
    }

    /** 交互式命令行 (纯函数): 与 ProotRunner.buildArgs 同形态, 收尾 /usr/bin/bash -l */
    public static List<String> buildArgs(String prootPath, String rootfsPath,
                                         String exchangePath, boolean bindSdcard) {
        List<String> a = new ArrayList<>();
        a.add(prootPath);
        a.add("-0");
        a.add("--kill-on-exit");
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
        a.add("TERM=xterm-256color");
        a.add("DEBIAN_FRONTEND=noninteractive");
        a.add("/usr/bin/bash");
        a.add("-l");
        return a;
    }

    /** proot 进程自身需要的环境 (纯函数): loader 回退 + 私有 tmp */
    public static List<String> buildEnv(String loaderPath, String tmpDir) {
        List<String> e = new ArrayList<>();
        e.add("PROOT_LOADER=" + loaderPath);
        e.add("PROOT_TMP_DIR=" + tmpDir);
        return e;
    }

    /** 由 Context 组装完整 Launch (rootfs READY 由调用方先判) */
    public static Launch build(Context ctx) {
        RootfsManager rm = new RootfsManager(ctx);
        rm.exchangeDir().mkdirs();
        java.io.File tmp = new java.io.File(ctx.getFilesDir(), "linux/tmp");
        tmp.mkdirs();
        List<String> args = buildArgs(
                Proot.binary(ctx).getAbsolutePath(),
                rm.rootfsDir().getAbsolutePath(),
                rm.exchangeDir().getAbsolutePath(),
                Environment.isExternalStorageManager());
        List<String> env = buildEnv(
                Proot.loader(ctx).getAbsolutePath(),
                tmp.getAbsolutePath());
        return new Launch(args.remove(0),
                args.toArray(new String[0]),
                env.toArray(new String[0]));
    }
}
