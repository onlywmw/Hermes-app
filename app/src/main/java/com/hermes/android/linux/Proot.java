package com.hermes.android.linux;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Proot — 内嵌 Linux 的用户态 chroot 引擎定位与探测。
 *
 * proot 为 green-green-avk/build-proot-android 的静态构建 (动态链接系统 bionic,
 * 无第三方依赖), 以 lib*.so 名义打进 jniLibs/arm64-v8a (Android 只释放
 * lib*.so 命名的文件, 且需 manifest extractNativeLibs="true"),
 * 系统安装时解压到 nativeLibraryDir — app 域唯一能执行原生二进制的位置。
 *
 * 真机验证过的 app 域 (untrusted_app) 约束:
 * - SELinux 禁止 exec /system/bin/tar → GNU tar/gzip 及其依赖随包 (libtar/libgzip...)
 * - SELinux 禁止创建硬链接 (dontaudit 无 avc 日志), 且静态 proot 的
 *   --link2symlink 模拟只走 seccomp 拦截, 在 app 域不生效 (runas_app 域反而生效):
 *   tar 解包遇硬链接条目 EPERM, 关掉 seccomp (PROOT_NO_SECCOMP) 则 ENOSYS。
 *   ⇒ rootfs 包必须预消除硬链接 (重打包 tar --hard-dereference) 并预装 python3/git
 * - dpkg 检查 geteuid()==0 → 必须 -0 fake root
 * - Termux 版 proot 在 app uid 下 dpkg 必报 "unable to securely remove .dpkg-tmp"
 *   (其 seccomp/loader 与 app uid 不兼容) → 换静态构建解决
 * - 陷阱: run-as 处于 runas_app 域, 无网络权限, 不能用 run-as 验证联网操作
 */
public class Proot {

    /** proot 可执行文件 (jniLibs 里叫 libproot.so, 解压后同名) */
    public static File binary(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    /** 随包 GNU tar (app 域不能 exec /system/bin/tar, 故自带; 仅 RootfsManager 解压用) */
    public static File tarBinary(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libtar.so");
    }

    /** proot loader (f2fs execve EACCES 回退机制必需, 经 PROOT_LOADER 注入) */
    public static File loader(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
    }

    /** 随包 gzip (GNU tar -z 会 fork gzip, 解压 rootfs 时经 tar -I 指定) */
    public static File gzipBinary(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libgzip.so");
    }

    /** 依赖库目录 (LD_LIBRARY_PATH 指到这里, libtar.so 的依赖库也在其中) */
    public static String libDir(Context ctx) {
        return ctx.getApplicationInfo().nativeLibraryDir;
    }

    public static boolean present(Context ctx) {
        return binary(ctx).isFile();
    }

    /**
     * 生死探测: app 域直接 exec nativeLibraryDir 里的 proot。
     * 成功返回版本行 ("proot --version" 首行), 失败返回以 "ERROR:" 开头的诊断。
     */
    public static String probe(Context ctx) {
        File bin = binary(ctx);
        if (!bin.isFile()) return "ERROR: proot 未打包 (" + bin.getAbsolutePath() + " 不存在)";
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "--version");
            pb.environment().put("LD_LIBRARY_PATH", libDir(ctx));
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder out = new StringBuilder();
            /* 先启读线程防管道缓冲区堵死, 再 waitFor */
            final Process fp = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(fp.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (out.length() > 0) out.append('\n');
                        out.append(line);
                    }
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "ERROR: proot --version 超时 (10s)";
            }
            reader.join(1000);
            String text = out.toString().trim();
            if (p.exitValue() == 0 && !text.isEmpty()) return text.split("\n")[0];
            return "ERROR: exit=" + p.exitValue() + " " + text;
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return "ERROR: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
