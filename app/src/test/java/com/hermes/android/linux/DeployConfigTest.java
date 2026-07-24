package com.hermes.android.linux;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * DeployConfig 纯逻辑单测 (M4): ssh config 模板 / 包装脚本 / 转义 / 脱敏。
 * (加密存储与真实 ssh 握手要 Android/真机, 不在本地单测范围)
 */
public class DeployConfigTest {

    @Test
    public void buildSshWrapper_passwordAuth() {
        String w = DeployConfig.buildSshWrapper(DeployConfig.AUTH_PASSWORD, "p@ss'w0rd",
                "192.168.1.10", 8022, "root");
        assertTrue(w.startsWith("#!/bin/sh"));
        assertTrue(w.contains("sshpass -e ssh"));
        assertTrue("密码单引号安全转义", w.contains("'p@ss'\\''w0rd'"));
        assertTrue(w.contains("-p 8022 root@192.168.1.10"));
        assertTrue(w.contains("StrictHostKeyChecking=no"));
        assertTrue("known_hosts 走 /dev/null 防 unlink EPERM", w.contains("UserKnownHostsFile=/dev/null"));
        assertTrue("localhost 夹具/抖动网络重试", w.contains("ConnectionAttempts=15"));
        assertFalse("密码认证不带 IdentityFile", w.contains("IdentityFile"));
    }

    @Test
    public void buildSshWrapper_keyAuth() {
        String w = DeployConfig.buildSshWrapper(DeployConfig.AUTH_KEY, "",
                "example.com", 22, "ubuntu");
        assertTrue(w.contains("exec ssh"));
        assertTrue(w.contains("-i /root/.ssh/mov_deploy_key"));
        assertTrue(w.contains("-p 22 ubuntu@example.com"));
        assertFalse(w.contains("sshpass"));
    }

    @Test
    public void buildScpWrapper_uppercasePortAndDst() {
        String w = DeployConfig.buildScpWrapper(DeployConfig.AUTH_PASSWORD, "pw",
                "h", 2222, "u");
        assertTrue("scp 端口用大写 -P", w.contains("-P 2222"));
        assertTrue("scp 强制 legacy 模式 (Ubuntu24 scp 默认 SFTP, 远端 sftp-server 不稳)",
                w.contains(" -O "));
        assertTrue(w.contains("u@h:"));
        assertTrue("缺省远端路径 /root/", w.contains("/root/"));
    }

    @Test
    public void buildTestCmd_inlineTarget() {
        String cmd = DeployConfig.buildTestCmd();
        assertTrue(cmd.contains("movssh"));
        assertTrue(cmd.contains("MOV_SSH_OK"));
        assertTrue(cmd.contains("ConnectTimeout=8"));
    }

    @Test
    public void shellSingleQuote_escapes() {
        assertEquals("'abc'", DeployConfig.shellSingleQuote("abc"));
        assertEquals("'a'\\''b'", DeployConfig.shellSingleQuote("a'b"));
        assertEquals("''", DeployConfig.shellSingleQuote(""));
    }

    @Test
    public void buildSshWrapper_badPortFallsBack22() {
        assertTrue(DeployConfig.buildSshWrapper(DeployConfig.AUTH_KEY, "", "h", 0, "u")
                .contains("-p 22"));
    }

    @Test
    public void maskSecret_hidesMiddle() {
        assertEquals("", DeployConfig.maskSecret(""));
        assertEquals("…(4 字符)", DeployConfig.maskSecret("abcd"));
        String m = DeployConfig.maskSecret("sk-1234567890abcd");
        assertTrue(m.startsWith("sk"));
        assertTrue(m.contains("…"));
        assertFalse(m.contains("123456"));
    }
}
