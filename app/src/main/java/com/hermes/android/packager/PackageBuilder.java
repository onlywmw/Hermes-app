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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
        /** 非阻断警告 (如检测到相对资源引用), 可空 */
        public String warning;
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
            /* 单文件自检: 相对资源引用不阻断打包, 但附警告提醒可能白屏 */
            r.warning = scanRelativeRefs(new String(htmlBytes, StandardCharsets.UTF_8));

            /* 应用名: 消毒 ≤16 单元, 空则回退文件名 */
            String label = BridgeValidator.sanitizeLabel(appName, AxmlPatcher.LABEL_LEN);
            if (label.isEmpty()) {
                label = BridgeValidator.sanitizeLabel(
                        html.getName().replaceAll("\\.[^.]+$", ""), AxmlPatcher.LABEL_LEN);
            }
            if (label.isEmpty()) label = "MOV App";
            String paddedLabel = padRight(label, AxmlPatcher.LABEL_LEN);

            String pkg = genPackageName(roomId, html.getName());
            File dir = new File(ctx.getFilesDir(), "packager");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File template = new File(dir, TEMPLATE_ASSET);
            copyAsset(ctx, TEMPLATE_ASSET, template);
            /* 模板编码自检: string pool 非 UTF-16 时中文名替换会静默失败, 直接拒绝 */
            if (!isTemplateUtf16(template)) {
                r.error = "模板不兼容, 请重新构建模板";
                return r;
            }

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

    /**
     * 稳定包名: 按 (roomId + html文件名) 哈希, 同一房间的同一 HTML 重复打包 = 同包名
     * 覆盖升级 (共享签名身份, 同 versionCode 覆盖安装可行)。等长 24 字符约束不变。
     */
    public static String genPackageName(String roomId, String htmlName) {
        String h = md5Hex((roomId != null ? roomId : "") + "/"
                + (htmlName != null ? htmlName : ""));
        if (h.length() < 12) return genPackageName(); /* 异常兜底: 退回随机 */
        return "com.movgen.h" + h.substring(0, 12);
    }

    /** 生成与占位符等长 (24) 的合法包名: com.movgen.a + 12 随机 [a-z0-9] */
    public static String genPackageName() {
        StringBuilder sb = new StringBuilder("com.movgen.a");
        for (int i = 0; i < 12; i++) sb.append(PKG_CHARS[RNG.nextInt(PKG_CHARS.length)]);
        return sb.toString();
    }

    private static String md5Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static final Pattern REL_REF = Pattern.compile(
            "(?:src|href)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * 单文件自检: 扫描 HTML 里的相对资源引用 (src/href 指向本地文件,
     * http(s)://、// CDN、data:、# 锚点等不算)。返回警告文本; 无引用返回 null。
     */
    public static String scanRelativeRefs(String html) {
        if (html == null) return null;
        Matcher m = REL_REF.matcher(html);
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        while (m.find()) {
            String v = m.group(1).trim();
            String lv = v.toLowerCase(Locale.ROOT);
            if (v.isEmpty() || lv.startsWith("http://") || lv.startsWith("https://")
                    || lv.startsWith("data:") || lv.startsWith("//") || v.startsWith("#")
                    || lv.startsWith("javascript:") || lv.startsWith("mailto:")
                    || lv.startsWith("tel:")) {
                continue;
            }
            refs.add(v);
        }
        if (refs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("警告: 检测到 ").append(refs.size())
                .append(" 处相对资源引用 (");
        int i = 0;
        for (String ref : refs) {
            if (i >= 3) { sb.append("等"); break; }
            if (i > 0) sb.append(", ");
            sb.append(ref);
            i++;
        }
        sb.append("), APK 内无法加载, 需改为单文件内联, 否则安装后可能白屏");
        return sb.toString();
    }

    /** 模板自检: shell-template.apk 的 manifest string pool 必须 UTF-16 */
    private static boolean isTemplateUtf16(File template) {
        try (ZipFile z = new ZipFile(template)) {
            ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null) return false;
            byte[] data;
            try (InputStream is = z.getInputStream(e)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                data = bos.toByteArray();
            }
            return AxmlPatcher.isUtf16StringPool(data);
        } catch (Exception e) {
            return false;
        }
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
