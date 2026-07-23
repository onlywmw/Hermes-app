package com.hermes.android.packager;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * ApkAssembler — 模板 APK 重打包 (纯 Java, JVM 可单测):
 * patch manifest 包名/应用名 + patch resources.arsc 包名 + 注入 assets/app.html。
 * 其余条目 (classes.dex / META-INF 元数据) 原样保留, STORED 条目保留存储方式。
 * 签名不在此层 (PackageBuilder 用 apksig 完成)。
 */
public final class ApkAssembler {

    private ApkAssembler() {}

    public static void assemble(File templateApk, byte[] htmlBytes,
                                String pkg, String paddedLabel, File out) throws IOException {
        try (ZipFile zin = new ZipFile(templateApk);
             FileOutputStream fos = new FileOutputStream(out);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {
            Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                byte[] data = readAll(zin.getInputStream(e));
                String name = e.getName();
                switch (name) {
                    case "AndroidManifest.xml":
                        if (AxmlPatcher.patchString(data, AxmlPatcher.PKG_PLACEHOLDER, pkg) == 0) {
                            throw new IOException("模板缺包名占位符, 请重新构建 shell-template.apk");
                        }
                        if (AxmlPatcher.patchString(data, AxmlPatcher.LABEL_PLACEHOLDER, paddedLabel) == 0) {
                            throw new IOException("模板缺应用名占位符, 请重新构建 shell-template.apk");
                        }
                        break;
                    case "resources.arsc":
                        AxmlPatcher.patchArscPackageName(data, pkg);
                        break;
                    case "assets/app.html":
                        data = htmlBytes;
                        break;
                    default:
                        break;
                }
                ZipEntry ne = new ZipEntry(name);
                if (e.getMethod() == ZipEntry.STORED) {
                    /* STORED 必须回填 size/crc (resources.arsc 通常是 STORED) */
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(data.length);
                    ne.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    ne.setCrc(crc.getValue());
                }
                zos.putNextEntry(ne);
                zos.write(data);
                zos.closeEntry();
            }
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
