package com.hermes.android.packager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * AxmlPatcher — 二进制 AXML string pool 等长替换 (纯 Java, JVM 可单测)。
 *
 * 壳模板工程 (templates/webapp-shell) 用定长占位符, 手机端在 APK 的
 * AndroidManifest.xml 二进制 string pool 里等长替换:
 * - 包名占位 24 字符 (ASCII): com.movgen.app0000000000
 * - 应用名占位 16 字符 (ASCII): MOVAPPPLACEHOLDR → UTF-16 池 16 单元, 空格补足
 *
 * 等长原则: 替换串编码后字节数必须与原串完全一致, 否则抛异常 (调用方负责 padding)。
 */
public final class AxmlPatcher {

    public static final String PKG_PLACEHOLDER = "com.movgen.app0000000000"; // 24 chars
    public static final String LABEL_PLACEHOLDER = "MOVAPPPLACEHOLDR";        // 16 chars
    public static final int PKG_LEN = 24;
    public static final int LABEL_LEN = 16;

    private static final int UTF8_FLAG = 0x00000100;

    private AxmlPatcher() {}

    /**
     * 模板编码自检: string pool 是否 UTF-16 (中文应用名等长替换的前提;
     * UTF-8 池下中文名长度换算会静默失败, 调用方应直接拒绝该模板)。
     */
    public static boolean isUtf16StringPool(byte[] axml) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
            if ((buf.getShort(0) & 0xFFFF) != 0x0003) return false;   /* RES_XML_TYPE */
            if ((buf.getShort(8) & 0xFFFF) != 0x0001) return false;   /* string pool chunk */
            int flags = buf.getInt(8 + 16);
            return (flags & UTF8_FLAG) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在 AXML 字节流中把 from 等长替换为 to。
     * @return 替换处数 (0 = 占位符没找到, 调用方应视为失败)
     */
    public static int patchString(byte[] axml, String from, String to) {
        ByteBuffer buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = buf.getShort(0) & 0xFFFF;
        if (fileType != 0x0003) throw new IllegalArgumentException("not binary AXML (RES_XML_TYPE)");
        /* string pool chunk 紧跟文件头 */
        int spOff = 8;
        int spType = buf.getShort(spOff) & 0xFFFF;
        if (spType != 0x0001) throw new IllegalArgumentException("string pool chunk not found");
        int stringCount = buf.getInt(spOff + 8);
        int flags = buf.getInt(spOff + 16);
        int stringsStart = buf.getInt(spOff + 20);
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        int offsetsBase = spOff + 28; /* ResStringPool_header = 28 bytes */
        int patched = 0;
        for (int i = 0; i < stringCount; i++) {
            int rel = buf.getInt(offsetsBase + i * 4);
            int pos = spOff + stringsStart + rel;
            if (utf8) {
                int[] u16len = readLen8(axml, pos);          /* UTF-16 长度 (忽略) */
                int[] u8len = readLen8(axml, pos + u16len[1]); /* UTF-8 字节长度 */
                int byteLen = u8len[0];
                int dataOff = pos + u16len[1] + u8len[1];
                String cur = new String(axml, dataOff, byteLen, StandardCharsets.UTF_8);
                if (cur.equals(from)) {
                    byte[] nb = to.getBytes(StandardCharsets.UTF_8);
                    if (nb.length != byteLen) {
                        throw new IllegalArgumentException(
                                "UTF-8 替换长度不等: " + nb.length + " != " + byteLen);
                    }
                    System.arraycopy(nb, 0, axml, dataOff, byteLen);
                    patched++;
                }
            } else {
                int[] r = readLen16(axml, pos);
                int units = r[0];
                int dataOff = pos + r[1];
                String cur = new String(axml, dataOff, units * 2, StandardCharsets.UTF_16LE);
                if (cur.equals(from)) {
                    byte[] nb = to.getBytes(StandardCharsets.UTF_16LE);
                    if (nb.length != units * 2) {
                        throw new IllegalArgumentException(
                                "UTF-16 替换长度不等: " + nb.length / 2 + " != " + units);
                    }
                    System.arraycopy(nb, 0, axml, dataOff, nb.length);
                    patched++;
                }
            }
        }
        return patched;
    }

    /**
     * resources.arsc 包名补丁: ResTable_package.name 是 128 个 char16 定长字段, 直接覆盖 + NUL 填充。
     * 注意: 无任何资源的壳模板 arsc 只有表头 + 空全局 string pool, 没有 package chunk —
     * 此时包名完全由 manifest 决定, 本方法跳过并返回 0 (不算失败)。
     * @return 打补丁的 package chunk 数 (0 = 模板无 package chunk, 已跳过)
     */
    public static int patchArscPackageName(byte[] arsc, String newName) {
        ByteBuffer buf = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN);
        int type = buf.getShort(0) & 0xFFFF;
        if (type != 0x0002) throw new IllegalArgumentException("not resources.arsc (RES_TABLE_TYPE)");
        int headerSize = buf.getShort(2) & 0xFFFF; /* ResChunk_header: type(0), headerSize(2), size(4) */
        byte[] nb = newName.getBytes(StandardCharsets.UTF_16LE);
        if (nb.length > 128 * 2 - 2) throw new IllegalArgumentException("包名过长");
        int patched = 0;
        int p = headerSize;
        while (p + 8 <= arsc.length) {
            int ptype = buf.getShort(p) & 0xFFFF;
            int chunkSize = buf.getInt(p + 4);
            if (chunkSize < 8) break; /* 防止损坏数据死循环 */
            if (ptype == 0x0200) { /* RES_PACKAGE_TYPE */
                int nameOff = p + 12; /* ResChunk_header(8) + package id(4) */
                if (nameOff + 128 * 2 > p + chunkSize) break; /* package chunk 过小, 放弃 */
                for (int i = 0; i < 128 * 2; i++) {
                    arsc[nameOff + i] = i < nb.length ? nb[i] : 0;
                }
                patched++;
            }
            p += chunkSize;
        }
        return patched;
    }

    /** UTF-8 string 长度字段: 首字节高位置位则为 2 字节 */
    private static int[] readLen8(byte[] a, int p) {
        int b0 = a[p] & 0xFF;
        if ((b0 & 0x80) != 0) {
            return new int[]{((b0 & 0x7F) << 8) | (a[p + 1] & 0xFF), 2};
        }
        return new int[]{b0, 1};
    }

    /** UTF-16 string 长度字段: 首 u16 高位置位则为 2 个 u16 */
    private static int[] readLen16(byte[] a, int p) {
        int u0 = (a[p] & 0xFF) | ((a[p + 1] & 0xFF) << 8);
        if ((u0 & 0x8000) != 0) {
            int u1 = (a[p + 2] & 0xFF) | ((a[p + 3] & 0xFF) << 8);
            return new int[]{((u0 & 0x7FFF) << 16) | u1, 4};
        }
        return new int[]{u0, 2};
    }
}
