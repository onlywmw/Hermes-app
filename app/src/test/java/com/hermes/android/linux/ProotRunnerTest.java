package com.hermes.android.linux;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

/**
 * ProotRunner 纯函数单测: buildArgs 命令行形态 / truncate 截断 / clampTimeout 钳制。
 * (exec 本身要真机 proot, 不在本地单测范围)
 */
public class ProotRunnerTest {

    // ==================== buildArgs ====================

    @Test
    public void buildArgs_matchesVerifiedShape() {
        List<String> a = ProotRunner.buildArgs(
                "/lib/libproot.so", "/data/linux/rootfs", "/data/linux-exchange", false,
                "uname -a");
        String joined = String.join(" ", a);
        assertTrue(joined.startsWith("/lib/libproot.so -0 --kill-on-exit --link2symlink -r /data/linux/rootfs"));
        assertTrue("必须 fake root (dpkg 检查 geteuid()==0)", a.contains("-0"));
        assertTrue("超时清场 (tracee 孤儿化会持锁卡死)", a.contains("--kill-on-exit"));
        assertTrue("app 域禁硬链接, dpkg status 备份必须 link2symlink", a.contains("--link2symlink"));
        assertTrue("必挂 /dev /proc /sys", joined.contains("-b /dev -b /proc -b /sys"));
        assertTrue("必挂交换目录", joined.contains("-b /data/linux-exchange:/exchange"));
        assertFalse("无 all-files 授权不挂 /sdcard", joined.contains("/sdcard"));
        assertTrue("工作目录 /root", joined.contains("-w /root"));
        assertTrue("必须显式注入 PATH (继承 Android PATH 会让 guest 命令全部 not found)",
                a.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"));
        assertTrue(a.contains("HOME=/root"));
        assertTrue(a.contains("DEBIAN_FRONTEND=noninteractive"));
        int bashIdx = a.indexOf("/usr/bin/bash");
        assertTrue(bashIdx > 0);
        assertEquals("-lc", a.get(bashIdx + 1));
        assertEquals("命令作为单一参数传递 (不经过宿主 shell 拼接)", "uname -a", a.get(bashIdx + 2));
    }

    @Test
    public void buildArgs_bindsSdcardWhenGranted() {
        List<String> a = ProotRunner.buildArgs("/p", "/r", "/e", true, "ls");
        assertTrue(a.contains("-b") && a.contains("/sdcard:/sdcard"));
    }

    @Test
    public void buildArgs_nullExchangeSkipsBind() {
        List<String> a = ProotRunner.buildArgs("/p", "/r", null, false, "ls");
        assertFalse(String.join(" ", a).contains("/exchange"));
    }

    // ==================== truncate ====================

    @Test
    public void truncate_shortUnchanged() {
        assertEquals("abc", ProotRunner.truncate("abc"));
        assertEquals("", ProotRunner.truncate(null));
        String at6000 = repeat('x', 6000);
        assertEquals(at6000, ProotRunner.truncate(at6000));
    }

    @Test
    public void truncate_longKeepsHeadAndTail() {
        String big = repeat('h', 2000) + repeat('m', 5000) + repeat('t', 2000);
        String out = ProotRunner.truncate(big);
        assertTrue("头 1500 保留", out.startsWith(repeat('h', 1500)));
        assertTrue("尾 4000 保留", out.endsWith(repeat('t', 2000)));
        assertTrue("标注原始长度", out.contains("9000"));
        assertTrue("总长受控", out.length() < 6000);
    }

    // ==================== clampTimeout ====================

    @Test
    public void clampTimeout_bounds() {
        assertEquals(120, ProotRunner.clampTimeout(0));
        assertEquals(120, ProotRunner.clampTimeout(-5));
        assertEquals(15, ProotRunner.clampTimeout(1));
        assertEquals(15, ProotRunner.clampTimeout(15));
        assertEquals(120, ProotRunner.clampTimeout(120));
        assertEquals(600, ProotRunner.clampTimeout(9999));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
