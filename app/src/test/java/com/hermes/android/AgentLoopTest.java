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
        return startAndApprove(roomId, brain, tools, null, sink);
    }

    private static AgentLoop startAndApprove(String roomId, FakeBrain brain, FakeTools tools,
                                             AgentLoop.Reviewer reviewer, FakeSink sink) throws Exception {
        java.util.Set<String> paths = new java.util.HashSet<>();
        com.hermes.android.agent.ToolRegistry registry = com.hermes.android.agent.ToolRegistry.build(
                tools, roomId, () -> paths,
                com.hermes.android.agent.ToolRegistry.policyForTest(java.util.Collections.emptySet()));
        AgentLoop loop = AgentLoop.startNew(roomId, "测试目标", brain, tools, registry,
                paths, reviewer, sink);
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
        java.util.Set<String> p1 = new java.util.HashSet<>();
        AgentLoop first = AgentLoop.startNew("room6", "任务1", brain, new FakeTools(),
                com.hermes.android.agent.ToolRegistry.build(new FakeTools(), "room6", () -> p1,
                        com.hermes.android.agent.ToolRegistry.policyForTest(java.util.Collections.emptySet())),
                p1, null, sink);
        assertNotNull(first);
        AgentLoop second = AgentLoop.startNew("room6", "任务2", brain, new FakeTools(), null, null, null, sink);
        assertNull("活跃 loop 存在时不应启动第二个", second);
        assertTrue(waitState(first, AgentLoop.State.PLAN_GATE));
        first.respondPlan(true, null);
        waitTerminal(first);
    }

    // ==================== ToolRegistry / DevicePolicy ====================

    @Test
    public void devicePolicy_tiers() {
        com.hermes.android.agent.ToolRegistry.DevicePolicy policy =
                com.hermes.android.agent.ToolRegistry.policyForTest(java.util.Collections.emptySet());
        assertNull("只读能力放行", policy.check("battery.status"));
        assertNull("动作类能力放行 (torch.on)", policy.check("torch.on"));
        assertNull("动作类能力放行 (tts.speak)", policy.check("tts.speak"));
        assertNotNull("电话拒绝", policy.check("telephony.call"));
        assertNotNull("短信读取拒绝 (隐私)", policy.check("sms.recent"));
        assertNotNull("file.ls 拒绝 (文件走 file.*)", policy.check("file.ls"));
        assertNotNull("触摸注入拒绝", policy.check("input.tap"));
        assertNotNull("空 capability 拒绝", policy.check(""));
    }

    @Test
    public void devicePolicy_hardwareUnavailable() {
        java.util.Set<String> un = new java.util.HashSet<>();
        un.add("torch.on"); un.add("torch.off");
        com.hermes.android.agent.ToolRegistry.DevicePolicy policy =
                com.hermes.android.agent.ToolRegistry.policyForTest(un);
        String deny = policy.check("torch.on");
        assertNotNull("无闪光灯设备应拒绝 torch", deny);
        assertTrue(deny.contains("不支持"));
        assertNull("其他动作不受影响", policy.check("vibrate"));
    }

    @Test
    public void registry_unknownToolRejected() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"termux.exec\",\"text\":\"ls\"}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("room11", brain, new FakeTools(), sink);
        waitTerminal(loop);
        assertEquals(AgentLoop.State.DONE, loop.getState());
        JSONObject step = sink.firstOfType("step");
        assertNotNull(step);
        assertFalse("未注册工具应拒绝", step.optBoolean("ok"));
        assertTrue(step.optString("result").contains("没有此工具"));
    }

    // ==================== v2: 评审团 ====================

    private static class FakeReviewer implements AgentLoop.Reviewer {
        private final JSONObject planResult;
        private final ConcurrentLinkedQueue<JSONObject> votes = new ConcurrentLinkedQueue<>();

        FakeReviewer(JSONObject planResult, JSONObject... voteSeq) {
            this.planResult = planResult;
            for (JSONObject v : voteSeq) votes.add(v);
        }

        @Override public JSONObject planReview(String goal, org.json.JSONArray plan) { return planResult; }
        @Override public JSONObject deliveryVote(String goal, String digest, org.json.JSONArray files) {
            JSONObject v = votes.poll();
            return v != null ? v : voteJson(1, 0);
        }
    }

    private static JSONObject voteJson(int pass, int fail) {
        JSONObject v = new JSONObject();
        try {
            v.put("pass", pass).put("fail", fail).put("pt", 10).put("ct", 5);
            org.json.JSONArray comments = new org.json.JSONArray();
            if (fail > 0) comments.put(new JSONObject().put("name", "R1").put("pass", false).put("reason", "有缺陷"));
            else comments.put(new JSONObject().put("name", "R1").put("pass", true).put("reason", "可以"));
            v.put("comments", comments);
        } catch (Exception ignored) {}
        return v;
    }

    @Test
    public void planReview_attachedToPlanCard() throws Exception {
        JSONObject pr = new JSONObject();
        pr.put("pt", 10).put("ct", 5);
        pr.put("items", new org.json.JSONArray().put(
                new JSONObject().put("name", "R1").put("role", "技术").put("comment", "注意性能")));
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("room7", brain, new FakeTools(), new FakeReviewer(pr), sink);
        waitTerminal(loop);
        JSONObject plan = sink.firstOfType("plan");
        assertNotNull(plan);
        assertEquals(1, plan.optJSONArray("reviews").length());
        assertEquals("注意性能", plan.optJSONArray("reviews").getJSONObject(0).optString("comment"));
    }

    @Test
    public void deliveryVote_pass() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("room8", brain, new FakeTools(),
                new FakeReviewer(null, voteJson(2, 0)), sink);
        waitTerminal(loop);
        assertEquals(AgentLoop.State.DONE, loop.getState());
        JSONObject deliver = sink.firstOfType("deliver");
        assertEquals(2, deliver.optInt("pass"));
        assertEquals(0, deliver.optInt("reworkRounds"));
        assertTrue("评审消耗单独计量", deliver.optInt("reviewTokens") > 0);
    }

    @Test
    public void deliveryVote_reworkOnceThenPass() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"v1\"}",      // 首轮交付 → 评审返工
                "{\"action\":\"file.write\",\"path\":\"a.html\",\"content\":\"fix\"}",
                "{\"action\":\"finish\",\"summary\":\"v2\"}");   // 返工轮交付 → 通过
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("room9", brain, tools,
                new FakeReviewer(null, voteJson(0, 2), voteJson(2, 0)), sink);
        waitTerminal(loop);
        assertEquals(AgentLoop.State.DONE, loop.getState());
        JSONObject deliver = sink.firstOfType("deliver");
        assertEquals("v2", deliver.optString("summary"));
        assertEquals(1, deliver.optInt("reworkRounds"));
        assertTrue("应有评审日志", sink.hasType("review"));
        assertTrue("返工轮独立预算可写文件", tools.writes.size() >= 1);
    }

    @Test
    public void deliveryVote_iterationCap() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"v1\"}",
                "{\"action\":\"finish\",\"summary\":\"v2\"}",
                "{\"action\":\"finish\",\"summary\":\"v3\"}");
        FakeSink sink = new FakeSink();
        // 三次投票全部返工 → 迭代上限后 DONE
        AgentLoop loop = startAndApprove("room10", brain, new FakeTools(),
                new FakeReviewer(null, voteJson(0, 2), voteJson(0, 2), voteJson(0, 2)), sink);
        waitTerminal(loop);
        assertEquals(AgentLoop.State.DONE, loop.getState());
        JSONObject deliver = sink.firstOfType("deliver");
        assertEquals(2, deliver.optInt("reworkRounds"));
    }
}
