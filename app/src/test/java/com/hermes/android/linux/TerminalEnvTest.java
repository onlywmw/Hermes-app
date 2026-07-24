package com.hermes.android.linux;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

/**
 * TerminalEnv 纯函数单测: 交互式命令行形态 / 环境注入 (M3)。
 */
public class TerminalEnvTest {

    @Test
    public void buildArgs_interactiveShape() {
        List<String> a = TerminalEnv.buildArgs(
                "/lib/libproot.so", "/data/linux/rootfs", "/data/linux-exchange", true);
        String joined = String.join(" ", a);
        assertTrue(joined.startsWith("/lib/libproot.so -0 --kill-on-exit --link2symlink -r /data/linux/rootfs"));
        assertTrue(joined.contains("-b /dev -b /proc -b /sys"));
        assertTrue(joined.contains("-b /data/linux-exchange:/exchange"));
        assertTrue("授权时挂 /sdcard", joined.contains("-b /sdcard:/sdcard"));
        assertTrue(joined.contains("-w /root"));
        assertTrue("显式注入 PATH", a.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"));
        assertTrue(a.contains("HOME=/root"));
        assertTrue("pty 终端类型", a.contains("TERM=xterm-256color"));
        int bashIdx = a.indexOf("/usr/bin/bash");
        assertTrue(bashIdx > 0);
        assertEquals("交互式 login shell (非 -lc)", "-l", a.get(bashIdx + 1));
        assertEquals("bash -l 是最后一个参数", a.size() - 1, bashIdx + 1);
    }

    @Test
    public void buildArgs_noSdcardWhenNotGranted() {
        List<String> a = TerminalEnv.buildArgs("/p", "/r", "/e", false);
        assertFalse(String.join(" ", a).contains("/sdcard"));
    }

    @Test
    public void buildEnv_loaderAndTmp() {
        List<String> e = TerminalEnv.buildEnv("/lib/libproot-loader.so", "/data/linux/tmp");
        assertTrue(e.contains("PROOT_LOADER=/lib/libproot-loader.so"));
        assertTrue(e.contains("PROOT_TMP_DIR=/data/linux/tmp"));
        assertEquals("不泄漏多余变量", 2, e.size());
    }
}
