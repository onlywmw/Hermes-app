package com.hermes.android.linux;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;

/**
 * DeployConfig — M4 部署服务器配置 (SSH)。
 *
 * 存储: 模型 key 同款 EncryptedSharedPreferences (AiProviderConfig.openEncryptedPrefs)。
 * 注入 rootfs (保存时自动):
 *   /root/.ssh/config           Host mov-deploy 段 (StrictHostKeyChecking=accept-new)
 *   /root/.ssh/mov_deploy_key   私钥 (600, 私钥认证时)
 *   /usr/local/bin/movssh       统一包装: 密码→sshpass -e, 私钥→裸 ssh (agent 直接用)
 *   /usr/local/bin/movscp       同上 (scp)
 *
 * agent prompt 只教别名: movssh mov-deploy 'cmd' / movscp f mov-deploy:/path。
 */
public class DeployConfig {

    private static final String TAG = "DeployConfig";
    public static final String SSH_ALIAS = "mov-deploy";
    public static final String AUTH_PASSWORD = "password";
    public static final String AUTH_KEY = "key";

    private static final String K_HOST = "deploy_host";
    private static final String K_PORT = "deploy_port";
    private static final String K_USER = "deploy_user";
    private static final String K_AUTH = "deploy_auth";
    private static final String K_SECRET = "deploy_secret";

    public String host = "";
    public int port = 22;
    public String user = "";
    public String authType = AUTH_PASSWORD;
    public String secret = "";   // 密码 或 私钥文本

    private final SharedPreferences prefs;

    public DeployConfig(Context ctx) {
        SharedPreferences enc = com.hermes.android.ai.AiProviderConfig.openEncryptedPrefs(ctx);
        prefs = enc != null ? enc : ctx.getSharedPreferences("deploy_prefs_fallback", Context.MODE_PRIVATE);
        host = prefs.getString(K_HOST, "");
        port = prefs.getInt(K_PORT, 22);
        user = prefs.getString(K_USER, "");
        authType = prefs.getString(K_AUTH, AUTH_PASSWORD);
        secret = prefs.getString(K_SECRET, "");
    }

    public boolean isConfigured() {
        return !host.trim().isEmpty() && !user.trim().isEmpty() && !secret.trim().isEmpty();
    }

    public static boolean isConfigured(Context ctx) {
        return new DeployConfig(ctx).isConfigured();
    }

    public void save(Context ctx) {
        prefs.edit()
                .putString(K_HOST, host.trim())
                .putInt(K_PORT, port > 0 ? port : 22)
                .putString(K_USER, user.trim())
                .putString(K_AUTH, AUTH_KEY.equals(authType) ? AUTH_KEY : AUTH_PASSWORD)
                .putString(K_SECRET, secret)
                .apply();
        writeToRootfs(ctx);
    }

    /** 脱敏密钥显示 (前 2 后 2) */
    public static String maskSecret(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.trim();
        if (t.length() <= 6) return "…(" + t.length() + " 字符)";
        return t.substring(0, 2) + "…" + t.substring(t.length() - 2) + " (" + t.length() + " 字符)";
    }

    // ==================== rootfs 注入 (纯逻辑可单测) ====================

    /** shell 单引号转义 (纯函数): ' → '\'' */
    public static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** movssh 包装脚本 (纯函数): 目标参数全内联。
     *  不写 ~/.ssh/config — app 域解压的文件属主是 app uid, guest fake root 下
     *  ssh 报 "Bad owner or permissions" (实踩), 故 host/port/user 内联进脚本;
     *  known_hosts 走 /dev/null — 否则 ssh 更新 known_hosts.old 时 unlink 被
     *  app 域 SELinux 拒 (实踩); agent 只需 movssh 'remote cmd'。 */
    public static String buildSshWrapper(String authType, String secret,
                                         String host, int port, String user) {
        String target = " -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
                + " -o ConnectionAttempts=15"
                + (AUTH_KEY.equals(authType) ? " -i /root/.ssh/mov_deploy_key" : "")
                + " -p " + (port > 0 ? port : 22)
                + " " + user + "@" + host;
        return "#!/bin/sh\n"
                + (AUTH_PASSWORD.equals(authType)
                        ? "SSHPASS=" + shellSingleQuote(secret) + " exec sshpass -e ssh" + target + " \"$@\"\n"
                        : "exec ssh" + target + " \"$@\"\n");
    }

    /** movscp 包装脚本 (纯函数): movscp <local> [remote_path(默认 /root/)]。
     *  scp 端口参数是 -P (大写), 远端拼 user@host:path。 */
    public static String buildScpWrapper(String authType, String secret,
                                         String host, int port, String user) {
        String opts = " -O -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
                + " -o ConnectionAttempts=15"
                + " -P " + (port > 0 ? port : 22)
                + (AUTH_KEY.equals(authType) ? " -i /root/.ssh/mov_deploy_key" : "");
        String pre = AUTH_PASSWORD.equals(authType)
                ? "SSHPASS=" + shellSingleQuote(secret) + " exec sshpass -e scp" : "exec scp";
        return "#!/bin/sh\n"
                + "DST=\"${2:-/root/}\"\n"
                + pre + opts + " \"$1\" " + user + "@" + host + ":\"$DST\"\n";
    }

    /** 测试连接命令 (纯函数): movssh 内联目标, 真实握手回显 MOV_SSH_OK */
    public static String buildTestCmd() {
        return "movssh -o ConnectTimeout=8 'echo MOV_SSH_OK'";
    }

    /** 写 私钥/包装脚本进 rootfs (不写 ~/.ssh/config, 属主问题见 buildSshWrapper);
     *  rootfs 未就绪则跳过 (安装后下次保存重写) */
    public void writeToRootfs(Context ctx) {
        try {
            RootfsManager rm = new RootfsManager(ctx);
            if (!rm.isReady()) return;
            File root = rm.rootfsDir();
            File sshDir = new File(root, "root/.ssh");
            sshDir.mkdirs();
            /* ~/.ssh/config 存在会导致 ssh Bad owner, 主动清掉 */
            new File(sshDir, "config").delete();
            if (AUTH_KEY.equals(authType)) {
                writeFile(new File(sshDir, "mov_deploy_key"),
                        secret.endsWith("\n") ? secret : secret + "\n", 384);
            } else {
                new File(sshDir, "mov_deploy_key").delete();
            }
            File binDir = new File(root, "usr/local/bin");
            binDir.mkdirs();
            writeFile(new File(binDir, "movssh"),
                    buildSshWrapper(authType, secret, host, port, user), 448);  // 700
            writeFile(new File(binDir, "movscp"),
                    buildScpWrapper(authType, secret, host, port, user), 448);
            Log.i(TAG, "已注入 rootfs: movssh/movscp (" + authType + ")");
        } catch (Exception e) {
            Log.w(TAG, "writeToRootfs: " + e.getMessage());
        }
    }

    private static void writeFile(File f, String content, int mode) throws Exception {
        f.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(content);
        }
        f.setReadable(true, true);
        f.setWritable(true, true);
        f.setExecutable(mode == 448, false);
    }
}
