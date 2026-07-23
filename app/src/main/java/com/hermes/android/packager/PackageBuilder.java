package com.hermes.android.packager;

import android.content.Context;
import android.util.Log;

import com.android.apksig.ApkSigner;
import com.hermes.android.StorageManager;
import com.hermes.android.bridge.BridgeValidator;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * PackageBuilder — 房间产出 HTML → 已签名真 APK (零外部依赖, 全程手机本地)。
 *
 * 流程: resolveWorkFile → 复制 assets/shell-template.apk → ApkAssembler 改包名/应用名
 * + 注入 app.html → apksig v1+v2 签名 (内嵌共享自签名密钥 movgen-sign.p12)。
 *
 * 签名决策: 密钥内嵌 assets = 所有 MOV 安装共享同一"MOV 生成"签名身份
 * (同设备/跨设备同名包可互相覆盖升级; 不可用于应用商店; 文档如实说明)。
 */
public class PackageBuilder {

    private static final String TAG = "PackageBuilder";
    private static final String TEMPLATE_ASSET = "shell-template.apk";
    private static final String KEYSTORE_ASSET = "movgen-sign.p12";
    private static final String KEY_ALIAS = "movgen";
    private static final char[] KEY_PASS = "movgen123".toCharArray();
    private static final long MAX_HTML_BYTES = 5L * 1024 * 1024;
    private static final char[] PKG_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    public static class Result {
        public boolean ok;
        public String error;
        public long sizeBytes;
        public String packageName;
        public File apkFile;
    }

    public static Result build(Context ctx, StorageManager sm,
                               String roomId, String path, String appName) {
        Result r = new Result();
        try {
            if (path == null || !(path.toLowerCase().endsWith(".html")
                    || path.toLowerCase().endsWith(".htm"))) {
                r.error = "只支持打包 HTML 文件";
                return r;
            }
            File html = sm.resolveWorkFile(roomId, path);
            if (html == null) { r.error = "文件不存在: " + path; return r; }
            byte[] htmlBytes = Files.readAllBytes(html.toPath());
            if (htmlBytes.length > MAX_HTML_BYTES) { r.error = "HTML 过大 (>5MB)"; return r; }

            /* 应用名: 消毒 ≤16 单元, 空则回退文件名 */
            String label = BridgeValidator.sanitizeLabel(appName, AxmlPatcher.LABEL_LEN);
            if (label.isEmpty()) {
                label = BridgeValidator.sanitizeLabel(
                        html.getName().replaceAll("\\.[^.]+$", ""), AxmlPatcher.LABEL_LEN);
            }
            if (label.isEmpty()) label = "MOV App";
            String paddedLabel = padRight(label, AxmlPatcher.LABEL_LEN);

            String pkg = genPackageName();
            File dir = new File(ctx.getFilesDir(), "packager");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File template = new File(dir, TEMPLATE_ASSET);
            copyAsset(ctx, TEMPLATE_ASSET, template);

            File unsigned = new File(dir, pkg + ".unsigned.apk");
            File signed = new File(dir, pkg + ".apk");
            ApkAssembler.assemble(template, htmlBytes, pkg, paddedLabel, unsigned);
            signApk(ctx, unsigned, signed);
            //noinspection ResultOfMethodCallIgnored
            unsigned.delete();

            r.ok = true;
            r.packageName = pkg;
            r.apkFile = signed;
            r.sizeBytes = signed.length();
            Log.i(TAG, "APK 打包完成: " + pkg + " (" + r.sizeBytes + "B) label=" + label);
            return r;
        } catch (Exception e) {
            Log.w(TAG, "打包失败", e);
            r.error = e.getMessage() != null ? e.getMessage() : "未知错误";
            return r;
        }
    }

    /** 生成与占位符等长 (24) 的合法包名: com.movgen.a + 12 随机 [a-z0-9] */
    public static String genPackageName() {
        StringBuilder sb = new StringBuilder("com.movgen.a");
        for (int i = 0; i < 12; i++) sb.append(PKG_CHARS[RNG.nextInt(PKG_CHARS.length)]);
        return sb.toString();
    }

    /** 应用名右侧空格补足到定长 (AXML 等长替换要求) */
    public static String padRight(String s, int units) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < units) sb.append(' ');
        return sb.length() > units ? sb.substring(0, units) : sb.toString();
    }

    private static void copyAsset(Context ctx, String name, File out) throws Exception {
        try (InputStream is = ctx.getAssets().open(name);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        }
    }

    private static void signApk(Context ctx, File in, File out) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = ctx.getAssets().open(KEYSTORE_ASSET)) {
            ks.load(is, KEY_PASS);
        }
        PrivateKey key = (PrivateKey) ks.getKey(KEY_ALIAS, KEY_PASS);
        X509Certificate cert = (X509Certificate) ks.getCertificate(KEY_ALIAS);
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                KEY_ALIAS, key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setMinSdkVersion(26)
                .build()
                .sign();
    }

    public String buildJson(Context ctx, StorageManager sm,
                            String roomId, String path, String appName) {
        Result r = build(ctx, sm, roomId, path, appName);
        try {
            JSONObject o = new JSONObject().put("ok", r.ok);
            if (r.ok) {
                o.put("sizeBytes", r.sizeBytes).put("packageName", r.packageName);
            } else {
                o.put("error", r.error);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"JSON 异常\"}";
        }
    }
}
