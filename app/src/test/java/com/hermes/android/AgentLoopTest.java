package com.hermes.android;

import static org.junit.Assert.*;

import com.hermes.android.agent.ActionParser;
import com.hermes.android.agent.AgentLoop;
import com.hermes.android.ai.AiClient;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AgentLoop 单测 (DESIGN_AGENT_LOOP v1.1):
 * 动作解析容错 / 完整闭环 / 计划外路径硬拒绝 / device.cmd 白名单 / 熔断。
 * Brain/Tools/LogSink 全部注入 fake, 不走网络与磁盘。
 */
public class AgentLoopTest {

    // ==================== ActionParser ====================

    @Test
    public void parse_cleanJson() {
        ActionParser.Result r = ActionParser.parse("{\"action\":\"finish\",\"summary\":\"done\"}");
        assertTrue(r.ok);
        assertEquals("finish", r.action.optString("action"));
    }

    @Test
    public void parse_fencedJson() {
        ActionParser.Result r = ActionParser.parse("```json\n{\"action\":\"file.list\"}\n```");
        assertTrue(r.ok);
        assertEquals("file.list", r.action.optString("action"));
    }

    @Test
    public void parse_proseAroundJson() {
        ActionParser.Result r = ActionParser.parse(
                "好的，我来执行:\n{\"action\":\"file.read\",\"path\":\"a.md\"}\n以上。");
        assertTrue(r.ok);
        assertEquals("a.md", r.action.optString("path"));
    }

    @Test
    public void parse_garbageFails() {
        assertFalse(ActionParser.parse("完全不是 JSON").ok);
        assertFalse(ActionParser.parse("{\"noAction\":1}").ok);
        assertFalse(ActionParser.parse(null).ok);
    }

    // ==================== 测试替身 ====================

    /** 脚本化大脑: 按队列依次返回响应 */
    private static class FakeBrain implements AgentLoop.Brain {
        private final ConcurrentLinkedQueue<String> script = new ConcurrentLinkedQueue<>();
        final List<String> seenInputs = new ArrayList<>();

        FakeBrain(String... responses) {
            for (String r : responses) script.add(r);
        }

        @Override
        public AiClient.AiResponse chat(String systemPrompt, String userText) {
            seenInputs.add(userText);
            String next = script.poll();
            if (next == null) return new AiClient.AiResponse(true, "{\"action\":\"finish\",\"summary\":\"完\"}");
            return new AiClient.AiResponse(true, next, 100, 50, false);
        }
    }

    private static class FakeTools implements AgentLoop.Tools {
        final List<String> writes = new ArrayList<>();
        final List<String> deviceCalls = new ArrayList<>();

        @Override public String fileList(String roomId) { return "[]"; }
        @Override public String fileRead(String roomId, String path) { return "内容"; }
        @Override public String fileWrite(String roomId, String path, String content) {
            writes.add(path);
            return "OK: 已写入";
        }
        @Override public String capabilityOf(String text) {
            if (text.contains("电话")) return "telephony.call";
            if (text.contains("文件")) return "file.ls";
            return "battery.status";
        }
        @Override public String deviceCmd(String text) {
            deviceCalls.add(text);
            return "电量 100%";
        }
    }

    private static class FakeSink implements AgentLoop.LogSink {
        final List<JSONObject> logs = new ArrayList<>();
        @Override public synchronized void onLog(JSONObject log) { logs.add(log); }
        boolean hasType(String t) {
            synchronized (this) {
                for (JSONObject l : logs) if (t.equals(l.optString("type"))) return true;
            }
            return false;
        }
        JSONObject firstOfType(String t) {
            synchronized (this) {
                for (JSONObject l : logs) if (t.equals(l.optString("type"))) return l;
            }
            return null;
        }
    }

    // ==================== 驱动辅助 ====================

    /** 等 loop 进入目标状态 (≤5s) */
    private static boolean waitState(AgentLoop loop, AgentLoop.State s) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 5000) {
            if (loop.getState() == s) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static AgentLoop startAndApprove(String roomId, FakeBrain brain,
                                             FakeTools tools, FakeSink sink) throws Exception {
        AgentLoop loop;
        synchronized (AgentLoop.class) {
            // 测试间互斥: 直接 new 等价物 — 走 startNew 需无活跃 loop
            loop = AgentLoop.startNew(roomId, "测试目标", brain, tools, sink);
        }
        assertNotNull("应能启动 loop", loop);
        assertTrue("应到达计划闸", waitState(loop, AgentLoop.State.PLAN_GATE));
        loop.respondPlan(true, null);
        return loop;
    }

    private static void waitTerminal(AgentLoop loop) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 8000) {
            if (!loop.isActive()) return;
            Thread.sleep(50);
        }
        fail("loop 未在 8s 内到达终态");
    }

    // ==================== 用例 ====================

    @Test
    public void fullFlow_planWriteFinish() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"game.html\",\"desc\":\"写游戏\"}]}",
                "{\"action\":\"file.write\",\"path\":\"game.html\",\"content\":\"<html>蛇</html>\"}",
                "{\"action\":\"finish\",\"summary\":\"贪吃蛇已完成\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startAndApprove("room1", brain, tools, sink);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.DONE, loop.getState());
        assertTrue(tools.writes.contains("game.html"));
        assertTrue("应有计划卡", sink.hasType("plan"));
        assertTrue("应有步骤日志", sink.hasType("step"));
        JSONObject deliver = sink.firstOfType("deliver");
        assertNotNull("应有交付卡", deliver);
        assertEquals("贪吃蛇已完成", deliver.optString("summary"));
        assertTrue("计量应累计", deliver.optInt("promptTokens") > 0);
        assertTrue("预估应给出", deliver.optInt("estTokens") > 0);
    }

    @Test
    public void outOfPlanWrite_hardRejected() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"只写 a\"}]}",
                "{\"action\":\"file.write\",\"path\":\"evil.js\",\"content\":\"x\"}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startAndApprove("room2", brain, tools, sink);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.DONE, loop.getState());
        assertFalse("计划外文件不得落盘", tools.writes.contains("evil.js"));
        JSONObject step = sink.firstOfType("step");
        assertNotNull(step);
        assertFalse("该步骤应标记失败", step.optBoolean("ok"));
        assertTrue(step.optString("result").contains("不在批准计划内"));
    }

    @Test
    public void deviceCmd_capabilityWhitelist() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"device.cmd\",\"path\":\"\",\"desc\":\"查电量\"}]}",
                "{\"action\":\"device.cmd\",\"text\":\"打电话给 10086\"}",
                "{\"action\":\"device.cmd\",\"text\":\"看看文件\"}",
                "{\"action\":\"device.cmd\",\"text\":\"电量多少\"}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startAndApprove("room3", brain, tools, sink);
        waitTerminal(loop);

        assertEquals("电话能力应被拒绝 (未执行)", 0,
                tools.deviceCalls.stream().filter(c -> c.contains("电话")).count());
        assertEquals("file.ls 应被拒绝 (未执行)", 0,
                tools.deviceCalls.stream().filter(c -> c.contains("文件")).count());
        assertEquals("电量应放行", 1,
                tools.deviceCalls.stream().filter(c -> c.contains("电量")).count());
    }

    @Test
    public void parseFailTwice_fuses() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "我不是 JSON",
                "我也不是 JSON");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startAndApprove("room4", brain, tools, sink);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.FAILED, loop.getState());
        assertTrue(sink.firstOfType("fail").optString("reason").contains("解析失败"));
    }

    @Test
    public void stopDuringExecution() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"file.list\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startAndApprove("room5", brain, tools, sink);
        loop.requestStop();
        waitTerminal(loop);

        assertEquals(AgentLoop.State.STOPPED, loop.getState());
        assertTrue(sink.hasType("stopped"));
    }

    @Test
    public void singleLoopMutex() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}");
        FakeSink sink = new FakeSink();
        AgentLoop first = AgentLoop.startNew("room6", "任务1", brain, new FakeTools(), sink);
        assertNotNull(first);
        AgentLoop second = AgentLoop.startNew("room6", "任务2", brain, new FakeTools(), sink);
        assertNull("活跃 loop 存在时不应启动第二个", second);
        assertTrue(waitState(first, AgentLoop.State.PLAN_GATE));
        first.respondPlan(true, null);
        waitTerminal(first);
    }
}
