package com.hermes.android.linux;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * RootfsManager 状态机纯逻辑单测 (安装流程要真机 proot/网络, 不在本地单测范围)。
 */
public class RootfsManagerTest {

    @Test
    public void normalizeRestored_transientStatesFallBack() {
        /* 进程死在安装中途 → 重启后不得谎称 DOWNLOADING/EXTRACTING/BOOTSTRAPPING */
        assertEquals(RootfsManager.State.NOT_INSTALLED,
                RootfsManager.normalizeRestored(RootfsManager.State.DOWNLOADING));
        assertEquals(RootfsManager.State.NOT_INSTALLED,
                RootfsManager.normalizeRestored(RootfsManager.State.EXTRACTING));
        assertEquals(RootfsManager.State.NOT_INSTALLED,
                RootfsManager.normalizeRestored(RootfsManager.State.BOOTSTRAPPING));
    }

    @Test
    public void normalizeRestored_terminalStatesKept() {
        assertEquals(RootfsManager.State.READY,
                RootfsManager.normalizeRestored(RootfsManager.State.READY));
        assertEquals(RootfsManager.State.ERROR,
                RootfsManager.normalizeRestored(RootfsManager.State.ERROR));
        assertEquals(RootfsManager.State.NOT_INSTALLED,
                RootfsManager.normalizeRestored(RootfsManager.State.NOT_INSTALLED));
    }

    @Test
    public void clearDirectory_removesAllAndKeepsDir() throws Exception {
        /* 重装前必须彻底清空: 带残渣解压 GNU tar 会报 "Cannot open: File exists" */
        java.io.File dir = java.nio.file.Files.createTempDirectory("rf").toFile();
        java.io.File sub = new java.io.File(dir, "usr/bin");
        assertTrue(sub.mkdirs());
        assertTrue(new java.io.File(sub, "perl").createNewFile());
        assertTrue(new java.io.File(dir, "etc").mkdirs());

        assertNull(RootfsManager.clearDirectory(dir));
        assertTrue("目录本身保留", dir.isDirectory());
        String[] left = dir.list();
        assertNotNull(left);
        assertEquals("必须全清", 0, left.length);

        /* 空调用幂等: 不存在/已空目录都返回 null */
        assertNull(RootfsManager.clearDirectory(dir));
        assertNull(RootfsManager.clearDirectory(new java.io.File(dir, "ghost")));
        dir.delete();
    }
}
