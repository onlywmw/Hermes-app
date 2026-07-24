package com.hermes.android;

import static org.junit.Assert.*;

import com.hermes.android.agent.AgentLoop;
import com.hermes.android.agent.ToolRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;

/**
 * ToolRegistry file.read 分页单测:
 * 默认 32k 页 / offset 续读 / length 钳制 / 越界拒绝 / 显式页脚 (读完/未完)。
 * 背景: 2k 截断时代大脑读不全自产文件反问用户; 32k 截断时代大脑幻觉"文件超 32k"。
 */
public class ToolRegistryTest {

    /** 造一个 content 可配的 Tools (其余方法用不到) */
    private static AgentLoop.Tools toolsWith(String content) {
        return new AgentLoop.Tools() {
            @Override public String fileList(String roomId) { return "[]"; }
            @Override public String fileRead(String roomId, String path) { return content; }
            @Override public String fileWrite(String roomId, String path, String c) { return "OK:"; }
            @Override public String capabilityOf(String text) { return "unknown"; }
            @Override public String deviceCmd(String text) { return "ok"; }
            @Override public String packageApk(String roomId, String path, String appName) { return "ERR: 不支持"; }
            @Override public String shellExec(String cmd, int timeoutSec) { return "exit=0\nok"; }
            @Override public String filePush(String roomId, String path) { return "{\"ok\":true}"; }
            @Override public String filePull(String roomId, String name) { return "{\"ok\":true}"; }
        };
    }

    private static ToolRegistry registryWith(String content) {
        return ToolRegistry.build(toolsWith(content), "room1",
                HashSet::new, new ToolRegistry.DevicePolicy(Collections.<String>emptySet()));
    }

    private static ToolRegistry.Result read(ToolRegistry r, String json) throws Exception {
        ToolRegistry.Tool t = r.find("file.read");
        assertNotNull(t);
        return t.handler.run(new JSONObject(json));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    public void read_smallFile_fullWithDoneFooter() throws Exception {
        ToolRegistry.Result res = read(registryWith("hello snake"), "{\"path\":\"a.html\"}");
        assertTrue(res.ok);
        assertTrue(res.text.startsWith("hello snake"));
        assertTrue("小文件必须给『读完』确定信号: " + res.text, res.text.contains("文件读完, 共 11 字符"));
    }

    @Test
    public void read_bigFile_firstPageHasContinueHint() throws Exception {
        String big = repeat('x', 40000);
        ToolRegistry.Result res = read(registryWith(big), "{\"path\":\"a.html\"}");
        assertTrue(res.ok);
        assertTrue("首页必须带续读指引: " + res.text.substring(res.text.length() - 80),
                res.text.contains("offset=32768"));
        assertTrue(res.text.contains("/40000 字符"));
    }

    @Test
    public void read_bigFile_secondPageCompletes() throws Exception {
        String big = repeat('y', 40000);
        ToolRegistry.Result res = read(registryWith(big), "{\"path\":\"a.html\",\"offset\":32768}");
        assertTrue(res.ok);
        assertTrue(res.text.contains("文件读完, 共 40000 字符"));
        /* 第二页正文长度 = 40000-32768 */
        assertTrue(res.text.startsWith(repeat('y', 100)));
    }

    @Test
    public void read_offsetBeyondRejected() throws Exception {
        ToolRegistry.Result res = read(registryWith("short"), "{\"path\":\"a.html\",\"offset\":999}");
        assertFalse(res.ok);
        assertTrue(res.text.contains("越界"));
    }

    @Test
    public void read_lengthClampedTo32k() throws Exception {
        String big = repeat('z', 100000);
        ToolRegistry.Result res = read(registryWith(big), "{\"path\":\"a.html\",\"length\":999999}");
        assertTrue(res.ok);
        /* 钳到 32768 → 继续提示 offset=32768 */
        assertTrue(res.text.contains("offset=32768"));
    }

    @Test
    public void read_errorPassthrough() throws Exception {
        ToolRegistry r = ToolRegistry.build(new AgentLoop.Tools() {
            @Override public String fileList(String roomId) { return "[]"; }
            @Override public String fileRead(String roomId, String path) { return "ERROR: 文件不存在"; }
            @Override public String fileWrite(String roomId, String path, String c) { return "OK:"; }
            @Override public String capabilityOf(String text) { return "unknown"; }
            @Override public String deviceCmd(String text) { return "ok"; }
            @Override public String packageApk(String roomId, String path, String appName) { return "ERR:"; }
            @Override public String shellExec(String cmd, int timeoutSec) { return "exit=0\nok"; }
            @Override public String filePush(String roomId, String path) { return "{\"ok\":true}"; }
            @Override public String filePull(String roomId, String name) { return "{\"ok\":true}"; }
        }, "room1", HashSet::new, new ToolRegistry.DevicePolicy(Collections.<String>emptySet()));
        ToolRegistry.Result res = read(r, "{\"path\":\"ghost.html\"}");
        assertFalse(res.ok);
        assertTrue(res.text.startsWith("ERROR:"));
    }

    @Test
    public void promptText_documentsPagination() {
        String p = registryWith("x").promptText();
        assertTrue("prompt 必须写明分页参数: " + p, p.contains("\"offset\":0"));
        assertTrue(p.contains("\"length\":32768"));
    }

    // ==================== app.package 结构化解析 ====================

    /** 造一个 packageApk 返回值可配的注册表 */
    private static ToolRegistry registryWithPackageRes(String packageRes) {
        return ToolRegistry.build(new AgentLoop.Tools() {
            @Override public String fileList(String roomId) { return "[]"; }
            @Override public String fileRead(String roomId, String path) { return ""; }
            @Override public String fileWrite(String roomId, String path, String c) { return "OK:"; }
            @Override public String capabilityOf(String text) { return "unknown"; }
            @Override public String deviceCmd(String text) { return "ok"; }
            @Override public String packageApk(String roomId, String path, String appName) { return packageRes; }
            @Override public String shellExec(String cmd, int timeoutSec) { return "exit=0\nok"; }
            @Override public String filePush(String roomId, String path) { return "{\"ok\":true}"; }
            @Override public String filePull(String roomId, String name) { return "{\"ok\":true}"; }
        }, "room1", HashSet::new, new ToolRegistry.DevicePolicy(Collections.<String>emptySet()));
    }

    private static ToolRegistry.Result runPackage(ToolRegistry r, String path) throws Exception {
        ToolRegistry.Tool t = r.find("app.package");
        assertNotNull(t);
        return t.handler.run(new JSONObject("{\"path\":\"" + path + "\"}"));
    }

    @Test
    public void appPackage_jsonResultParsed() throws Exception {
        ToolRegistry.Result res = runPackage(registryWithPackageRes(
                "{\"ok\":true,\"file\":\"snake.apk\",\"msg\":\"snake.apk · 24KB\"}"), "snake.html");
        assertTrue(res.ok);
        assertEquals("snake.apk", res.produced);
        assertEquals("snake.apk · 24KB", res.text);
    }

    @Test
    public void appPackage_jsonErrorPropagated() throws Exception {
        ToolRegistry.Result res = runPackage(registryWithPackageRes(
                "{\"ok\":false,\"error\":\"模板不兼容, 请重新构建模板\"}"), "snake.html");
        assertFalse(res.ok);
        assertTrue(res.text.contains("模板不兼容"));
    }

    @Test
    public void appPackage_legacyFormatFallback() throws Exception {
        /* 旧文本格式仍兼容; 文件名含 " · " 时旧逻辑切错但不抛异常 */
        ToolRegistry.Result res = runPackage(registryWithPackageRes(
                "OK: snake.apk · 24KB · com.mov.test"), "snake.html");
        assertTrue(res.ok);
        assertEquals("snake.apk", res.produced);
        ToolRegistry.Result weird = runPackage(registryWithPackageRes(
                "OK: done"), "snake.html");
        assertTrue("无分隔符的旧格式不得抛异常", weird.ok);
        assertEquals("done", weird.produced);
    }

    // ==================== linuxAvailable 门控 (内嵌 Linux) ====================

    @Test
    public void linuxTools_hiddenWhenUnavailable() {
        ToolRegistry r = registryWith("x");
        assertNull("rootfs 未就绪不得注册 shell.exec", r.find("shell.exec"));
        assertNull(r.find("file.push"));
        assertNull(r.find("file.pull"));
        assertFalse("prompt 不得出现 shell.exec", r.promptText().contains("shell.exec"));
    }

    @Test
    public void linuxTools_registeredWhenAvailable() throws Exception {
        ToolRegistry r = ToolRegistry.build(toolsWith("x"), "room1",
                HashSet::new, new ToolRegistry.DevicePolicy(Collections.<String>emptySet()), true);
        assertNotNull(r.find("shell.exec"));
        assertNotNull(r.find("file.push"));
        assertNotNull(r.find("file.pull"));
        assertTrue("prompt 必须写明 cmd 参数: " + r.promptText(),
                r.promptText().contains("\"cmd\""));
        ToolRegistry.Result res = r.find("shell.exec").handler.run(
                new JSONObject("{\"cmd\":\"uname -a\"}"));
        assertTrue(res.ok);
        assertTrue(res.text.startsWith("exit=0"));
        ToolRegistry.Result empty = r.find("shell.exec").handler.run(new JSONObject("{}"));
        assertFalse("空 cmd 应拒绝", empty.ok);
    }
}
