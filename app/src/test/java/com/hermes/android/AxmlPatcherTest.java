package com.hermes.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.hermes.android.packager.ApkAssembler;
import com.hermes.android.packager.AxmlPatcher;
import com.hermes.android.packager.PackageBuilder;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unit tests for the MOV in-app APK packager core: AXML string patching,
 * ARSC package-name patching, and full assembly of the shell template.
 * The final badging assertion runs through aapt2 when it is available on
 * the host (Android SDK build-tools); otherwise it is skipped.
 */
public class AxmlPatcherTest {

    private static File templateFile() {
        // Unit tests run with the app module dir as working directory.
        return new File("src/main/assets/shell-template.apk");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static byte[] entryBytes(ZipFile zip, String name) throws Exception {
        ZipEntry e = zip.getEntry(name);
        assertTrue("template missing entry " + name, e != null);
        InputStream in = zip.getInputStream(e);
        byte[] b = readAll(in);
        in.close();
        return b;
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] utf16le(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            out.write(c & 0xFF);
            out.write((c >> 8) & 0xFF);
        }
        return out.toByteArray();
    }

    @Test
    public void patchManifest_packageAndChineseLabel() throws Exception {
        assertTrue("shell template asset must exist", templateFile().isFile());
        ZipFile zip = new ZipFile(templateFile());
        byte[] manifest = entryBytes(zip, "AndroidManifest.xml");
        byte[] arsc = entryBytes(zip, "resources.arsc");
        zip.close();

        String newPkg = "com.movgen.atest00000001"; // exactly 24 chars, no padding needed
        String pkg24 = PackageBuilder.padRight(newPkg, AxmlPatcher.PKG_LEN);
        assertEquals(AxmlPatcher.PKG_LEN, pkg24.length());
        String label = PackageBuilder.padRight("贪吃蛇", AxmlPatcher.LABEL_LEN);
        assertEquals(AxmlPatcher.LABEL_LEN, label.length());

        int pkgRepl = AxmlPatcher.patchString(manifest, AxmlPatcher.PKG_PLACEHOLDER, pkg24);
        assertTrue("package placeholder should be replaced at least once", pkgRepl >= 1);
        int lblRepl = AxmlPatcher.patchString(manifest, AxmlPatcher.LABEL_PLACEHOLDER, label);
        assertTrue("label placeholder should be replaced at least once", lblRepl >= 1);

        // Placeholder strings must be gone, new content present (UTF-16 or UTF-8 pool).
        assertTrue(!contains(manifest, utf16le(AxmlPatcher.PKG_PLACEHOLDER)));
        assertTrue(!contains(manifest, AxmlPatcher.PKG_PLACEHOLDER.getBytes(StandardCharsets.UTF_8)));
        assertTrue(contains(manifest, utf16le(pkg24))
                || contains(manifest, pkg24.getBytes(StandardCharsets.UTF_8)));
        assertTrue(contains(manifest, utf16le("贪吃蛇"))
                || contains(manifest, "贪吃蛇".getBytes(StandardCharsets.UTF_8)));

        int origLen = arsc.length;
        boolean hadPkgChunk = contains(arsc, utf16le(AxmlPatcher.PKG_PLACEHOLDER));
        int arscPatched = AxmlPatcher.patchArscPackageName(arsc, pkg24);
        assertEquals(hadPkgChunk ? 1 : 0, arscPatched);
        if (hadPkgChunk) {
            assertTrue(!contains(arsc, utf16le(AxmlPatcher.PKG_PLACEHOLDER)));
            assertTrue(contains(arsc, utf16le(pkg24)));
        }
        assertEquals(origLen, arsc.length);
    }

    @Test
    public void assemble_producesValidApk_matchingAapt2Badging() throws Exception {
        ZipFile zip = new ZipFile(templateFile());
        byte[] manifest = entryBytes(zip, "AndroidManifest.xml");
        byte[] arsc = entryBytes(zip, "resources.arsc");
        zip.close();

        String pkg24 = PackageBuilder.padRight("com.movgen.aunittest0001", AxmlPatcher.PKG_LEN);
        String label = PackageBuilder.padRight("贪吃蛇", AxmlPatcher.LABEL_LEN);
        AxmlPatcher.patchString(manifest, AxmlPatcher.PKG_PLACEHOLDER, pkg24);
        AxmlPatcher.patchString(manifest, AxmlPatcher.LABEL_PLACEHOLDER, label);
        AxmlPatcher.patchArscPackageName(arsc, pkg24);

        File out = File.createTempFile("mov-pack-test", ".apk");
        out.deleteOnExit();
        byte[] html = "<html><body>snake</body></html>".getBytes(StandardCharsets.UTF_8);
        ApkAssembler.assemble(templateFile(), html, pkg24, label, out);
        assertTrue(out.length() > 1000);

        // Verify the assembled zip keeps every template entry.
        ZipFile assembled = new ZipFile(out);
        assertTrue(assembled.getEntry("AndroidManifest.xml") != null);
        assertTrue(assembled.getEntry("classes.dex") != null);
        ZipEntry htmlEntry = assembled.getEntry("assets/app.html");
        assertTrue(htmlEntry != null);
        byte[] roundTrip = readAll(assembled.getInputStream(htmlEntry));
        assembled.close();
        assertEquals(new String(html, StandardCharsets.UTF_8),
                new String(roundTrip, StandardCharsets.UTF_8));

        // Host-side verification with aapt2 when available.
        String aapt2 = findAapt2();
        if (aapt2 == null) return; // skip badging assertion on hosts without SDK
        Process p = new ProcessBuilder(aapt2, "dump", "badging", out.getAbsolutePath())
                .redirectErrorStream(true).start();
        String dump = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        p.waitFor();
        assertTrue("aapt2 badging should report patched package, got:\n" + dump,
                dump.contains("package: name='com.movgen.aunittest0001'"));
        assertTrue("aapt2 badging should report patched label, got:\n" + dump,
                dump.contains("label='贪吃蛇"));
    }

    private static String findAapt2() {
        String home = System.getenv("ANDROID_HOME");
        if (home == null) home = System.getenv("ANDROID_SDK_ROOT");
        if (home == null) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) home = local + "\\Android\\Sdk";
        }
        if (home == null) return null;
        File btDir = new File(home, "build-tools");
        File[] versions = btDir.listFiles();
        if (versions == null) return null;
        java.util.Arrays.sort(versions);
        for (int i = versions.length - 1; i >= 0; i--) {
            File f = new File(versions[i], "aapt2.exe");
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    @Test
    public void genPackageName_isRandomAndValidLength() {
        String a = PackageBuilder.genPackageName();
        String b = PackageBuilder.genPackageName();
        assertEquals(AxmlPatcher.PKG_LEN, a.length());
        assertEquals(AxmlPatcher.PKG_LEN, b.length());
        assertTrue(a.startsWith("com.movgen.a"));
        assertTrue(!a.equals(b));
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            assertTrue(c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'));
        }
    }

    @Test
    public void padRight_padsAndKeepsLongInput() {
        assertEquals("abc             ", PackageBuilder.padRight("abc", 16));
        assertEquals(16, PackageBuilder.padRight("abc", 16).length());
        String longInput = "0123456789abcdefg"; // 17 chars -> truncated to 16
        assertEquals("0123456789abcdef", PackageBuilder.padRight(longInput, 16));
    }

    @Test
    public void genPackageName_stableHashPerRoomAndFile() {
        String a = PackageBuilder.genPackageName("room1", "snake.html");
        String a2 = PackageBuilder.genPackageName("room1", "snake.html");
        String b = PackageBuilder.genPackageName("room1", "menu.html");
        String c = PackageBuilder.genPackageName("room2", "snake.html");
        assertEquals("同房间同文件必须同包名 (覆盖升级)", a, a2);
        assertTrue("换文件应换包名", !a.equals(b));
        assertTrue("换房间应换包名", !a.equals(c));
        assertEquals(AxmlPatcher.PKG_LEN, a.length());
        assertTrue(a.startsWith("com.movgen."));
        for (int i = 0; i < a.length(); i++) {
            char ch = a.charAt(i);
            assertTrue(ch == '.' || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
        }
    }

    @Test
    public void scanRelativeRefs_flagsLocalRefsOnly() {
        String w = PackageBuilder.scanRelativeRefs(
                "<html><script src=\"game.js\"></script><link href=\"img/a.css\">"
                        + "<script src=\"https://cdn.x.com/a.js\"></script>"
                        + "<img src=\"data:image/png;base64,xx\"><a href=\"#top\">t</a></html>");
        assertTrue("应报相对引用警告: " + w, w != null && w.contains("game.js"));
        assertTrue("http/data/# 不应误报: " + w, !w.contains("cdn.x.com") && !w.contains("data:"));
        assertEquals(null, PackageBuilder.scanRelativeRefs(
                "<html><script src=\"https://a.com/x.js\"></script></html>"));
        assertEquals(null, PackageBuilder.scanRelativeRefs(null));
    }

    @Test
    public void isUtf16StringPool_templateAndFlippedFlag() throws Exception {
        byte[] manifest;
        try (ZipFile z = new ZipFile(templateFile())) {
            manifest = entryBytes(z, "AndroidManifest.xml");
        }
        assertTrue("模板 string pool 必须 UTF-16", AxmlPatcher.isUtf16StringPool(manifest));
        /* 置 UTF-8 flag (flags 在 string pool 头 +16, LE int → 第 2 字节 bit0) */
        byte[] flipped = manifest.clone();
        flipped[8 + 16 + 1] |= 0x01;
        assertTrue("UTF-8 池应判为不兼容", !AxmlPatcher.isUtf16StringPool(flipped));
        assertTrue("垃圾字节应判为不兼容", !AxmlPatcher.isUtf16StringPool(new byte[4]));
    }
}
