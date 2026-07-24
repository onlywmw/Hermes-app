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
        final List<String> shellCalls = new ArrayList<>();

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
        @Override public String packageApk(String roomId, String path, String appName) {
            if (!writes.contains(path)) return "ERR: 文件不存在: " + path;
            return "OK: snake.apk · 24KB · com.mov.test";
        }
        @Override public String shellExec(String cmd, int timeoutSec) {
            shellCalls.add(cmd);
            return "exit=0\nfake-ok";
        }
        @Override public String filePush(String roomId, String path) { return "{\"ok\":true}"; }
        @Override public String filePull(String roomId, String name) { return "{\"ok\":true}"; }
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
        lastSink = sink;
        AgentLoop loop = AgentLoop.startNew(roomId, "测试目标", brain, tools, registry,
                paths, reviewer, sink);
        assertNotNull("应能启动 loop", loop);
        assertTrue("应到达计划闸", waitState(loop, AgentLoop.State.PLAN_GATE));
        loop.respondPlan(true, null);
        return loop;
    }

    private static FakeSink lastSink;
    private static void waitTerminal(AgentLoop loop) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 8000) {
            if (!loop.isActive()) return;
            Thread.sleep(50);
        }
        StringBuilder sb = new StringBuilder("loop 未在 8s 内到达终态, state=" + loop.getState() + ", logs=");
        if (lastSink != null) for (JSONObject l : lastSink.logs) sb.append(l.optString("type")).append(',');
        fail(sb.toString());
    }

    /** 等 sink 出现某类日志 (≤5s) */
    private static boolean waitLog(FakeSink sink, String type) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 5000) {
            if (sink.hasType(type)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    /** P1: 自动确认写入预览 (不涉及预览交互的旧用例用, 断言焦点不变) */
    private static void autoApprovePreviews(AgentLoop loop, FakeSink sink) {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            while (loop.isActive() && System.currentTimeMillis() - t0 < 10000) {
                if (sink.hasType("filePreview")) loop.respondFileWrite(true);
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        });
        t.setDaemon(true);
        t.start();
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
        autoApprovePreviews(loop, sink);
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
    public void inPlanWrite_noPreviewGate() throws Exception {
        /* 闸门精简: 计划内写入不再弹预览, 批准计划即授权, 直接落盘 */
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"写 a\"}]}",
                "{\"action\":\"file.write\",\"path\":\"a.html\",\"content\":\"<html>hi</html>\"}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startAndApprove("room2", brain, tools, sink);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.DONE, loop.getState());
        assertFalse("计划内写入不应弹预览", sink.hasType("filePreview"));
        assertTrue("计划内写入直接落盘", tools.writes.contains("a.html"));
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
    public void failCarriesPartialArtifacts() throws Exception {
        /* 失败三问: fail 事件必须带 人话原因 + 部分产物 files + 进度 stepsDone + 原目标 goal */
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"file.write\",\"path\":\"a.html\",\"content\":\"<html>ok</html>\"}",
                "我不是 JSON",
                "我也不是 JSON");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startAndApprove("room4b", brain, tools, sink);
        autoApprovePreviews(loop, sink);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.FAILED, loop.getState());
        JSONObject fail = sink.firstOfType("fail");
        assertNotNull("应有 fail 事件", fail);
        assertTrue("带人话原因", fail.optString("reason").length() > 0);
        assertEquals("带原目标 (供重开)", "测试目标", fail.optString("goal"));
        assertEquals("已完成 1 步", 1, fail.optInt("stepsDone"));
        assertTrue("部分产物含 a.html", fail.optJSONArray("files").toString().contains("a.html"));
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
        @Override public JSONObject deliveryVote(String goal, String digest, org.json.JSONArray files,
                                                 String productsDigest) {
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
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"},"
                        + "{\"action\":\"file.write\",\"path\":\"b.html\",\"desc\":\"y\"},"
                        + "{\"action\":\"app.package\",\"path\":\"a.html\",\"desc\":\"z\"}]}",
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
    public void planReview_skippedForSmallPlan() throws Exception {
        /* ≤2 步的小事不开评审会 (借鉴 OpenCodeReview: 小变更跳 PLAN 省钱) */
        JSONObject pr = new JSONObject();
        pr.put("pt", 10).put("ct", 5);
        pr.put("items", new org.json.JSONArray().put(
                new JSONObject().put("name", "R1").put("role", "技术").put("comment", "注意性能")));
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("room7b", brain, new FakeTools(), new FakeReviewer(pr), sink);
        waitTerminal(loop);
        JSONObject plan = sink.firstOfType("plan");
        assertNotNull(plan);
        assertFalse("1 步计划不应附带评审", plan.has("reviews"));
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
        autoApprovePreviews(loop, sink);
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

    // ==================== M-QUALITY: 评审失败不算过 / 未评审标记 ====================

    @Test
    public void deliveryVote_abstainIsNotPass() throws Exception {
        /* 评审调用失败(0 有效票) → not_reviewed, 不再 fail-open 视为通过 */
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("roomQ1", brain, new FakeTools(),
                new FakeReviewer(null, voteJson(0, 0)), sink);
        waitTerminal(loop);
        assertEquals(AgentLoop.State.DONE, loop.getState());
        JSONObject deliver = sink.firstOfType("deliver");
        assertEquals("not_reviewed", deliver.optString("reviewState"));
        assertFalse("未评审不得计入通过票", deliver.optInt("pass") > 0);
    }

    @Test
    public void deliveryVote_passedState() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("roomQ2", brain, new FakeTools(),
                new FakeReviewer(null, voteJson(2, 0)), sink);
        waitTerminal(loop);
        JSONObject deliver = sink.firstOfType("deliver");
        assertEquals("passed", deliver.optString("reviewState"));
    }

    @Test
    public void deliveryVote_reworkedState() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"v1\"}",
                "{\"action\":\"file.write\",\"path\":\"a.html\",\"content\":\"fix\"}",
                "{\"action\":\"finish\",\"summary\":\"v2\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("roomQ3", brain, new FakeTools(),
                new FakeReviewer(null, voteJson(0, 2), voteJson(2, 0)), sink);
        autoApprovePreviews(loop, sink);
        waitTerminal(loop);
        JSONObject deliver = sink.firstOfType("deliver");
        assertEquals("reworked", deliver.optString("reviewState"));
    }

    @Test
    public void deliver_carriesChecklist() throws Exception {
        /* finish summary 的 ✅/⚠️ 自检清单必须进交付事件 */
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"做好了。\\n- ✅ 界面: 真实现\\n- ⚠️ 扫一扫: 演示版(无相机)\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("roomQ4", brain, new FakeTools(), sink);
        autoApprovePreviews(loop, sink);
        waitTerminal(loop);
        JSONObject deliver = sink.firstOfType("deliver");
        org.json.JSONArray cl = deliver.optJSONArray("checklist");
        assertNotNull("交付事件必须带 checklist", cl);
        assertEquals(2, cl.length());
        assertTrue(cl.getJSONObject(0).optBoolean("real"));
        assertFalse(cl.getJSONObject(1).optBoolean("real"));
        assertEquals("扫一扫: 演示版(无相机)", cl.getJSONObject(1).optString("text"));
    }

    @Test
    public void deliver_checklistAbsentWhenNoMarkers() throws Exception {
        /* summary 无 ✅/⚠️ 行 → checklist 空数组, 不报错 */
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"普通交付\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("roomQ5", brain, new FakeTools(), sink);
        autoApprovePreviews(loop, sink);
        waitTerminal(loop);
        JSONObject deliver = sink.firstOfType("deliver");
        assertEquals(0, deliver.optJSONArray("checklist").length());
    }

    @Test
    public void appPackage_producesApk() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"snake.html\",\"desc\":\"写游戏\"}]}",
                "{\"action\":\"file.write\",\"path\":\"snake.html\",\"content\":\"<html>蛇</html>\"}",
                "{\"action\":\"app.package\",\"path\":\"snake.html\"}",
                "{\"action\":\"finish\",\"summary\":\"游戏+APK 交付\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("room12", brain, tools, sink);
        autoApprovePreviews(loop, sink);
        waitTerminal(loop);
        assertEquals(AgentLoop.State.DONE, loop.getState());
        JSONObject deliver = sink.firstOfType("deliver");
        String files = deliver.optJSONArray("files").toString();
        assertTrue("产出含 html", files.contains("snake.html"));
        assertTrue("产出含 apk", files.contains("snake.apk"));
    }

    // ==================== P1: 写盘前强制预览 ====================

    @Test
    public void outOfPlanWrite_previewApproveWrites() throws Exception {
        /* 计划外写入: 弹预览 → 用户批准 → 落盘并临时授权该路径 */
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"写 a\"}]}",
                "{\"action\":\"file.write\",\"path\":\"b.html\",\"content\":\"<html>hi</html>\"}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startAndApprove("room20", brain, tools, sink);
        assertTrue("计划外写入应弹预览", waitLog(sink, "filePreview"));
        JSONObject pv = sink.firstOfType("filePreview");
        assertEquals("b.html", pv.optString("path"));
        assertTrue("应标记计划外", pv.optBoolean("outOfPlan"));
        assertFalse("确认前不得落盘", tools.writes.contains("b.html"));
        loop.respondFileWrite(true);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.DONE, loop.getState());
        assertTrue("确认后应落盘", tools.writes.contains("b.html"));
    }

    @Test
    public void outOfPlanWrite_previewRejectSkips() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"写 a\"}]}",
                "{\"action\":\"file.write\",\"path\":\"b.html\",\"content\":\"x\"}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startAndApprove("room21", brain, tools, sink);
        assertTrue("计划外写入应弹预览", waitLog(sink, "filePreview"));
        loop.respondFileWrite(false);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.DONE, loop.getState());
        assertFalse("驳回后不得落盘", tools.writes.contains("b.html"));
        JSONObject step = sink.firstOfType("step");
        assertNotNull(step);
        assertFalse("驳回的写入步骤应标记失败", step.optBoolean("ok"));
        assertTrue(step.optString("result").contains("驳回"));
    }

    @Test
    public void clarifyBeforePlan() throws Exception {
        /* 理解闸: 场景有多种合理解读时, 大脑先 clarify → 用户回答 → 再出计划(带 understanding) */
        FakeBrain brain = new FakeBrain(
                "{\"clarify\":{\"question\":\"点单应用谁用?\",\"options\":[\"顾客扫码点单\",\"老板收银记账\"]}}",
                "{\"understanding\":{\"user\":\"烧烤摊老板\",\"scenario\":\"烤炉前单手操作\",\"loop\":\"开台→加菜→结账\",\"guess\":\"6张桌\"},\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"finish\",\"summary\":\"done\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();
        java.util.Set<String> paths = new java.util.HashSet<>();
        com.hermes.android.agent.ToolRegistry registry = com.hermes.android.agent.ToolRegistry.build(
                tools, "roomC1", () -> paths,
                com.hermes.android.agent.ToolRegistry.policyForTest(java.util.Collections.emptySet()));
        AgentLoop loop = AgentLoop.startNew("roomC1", "做个点单应用", brain, tools, registry,
                paths, null, sink);
        assertNotNull(loop);

        /* 应先挂起等澄清, 而不是直接到计划闸 */
        assertTrue("应发出 clarify 提问", waitLog(sink, "ask"));
        assertEquals("clarify 不消耗计划重试", AgentLoop.State.PLANNING, loop.getState());
        JSONObject ask = sink.firstOfType("ask");
        assertEquals("点单应用谁用?", ask.optString("question"));
        assertNotNull("ask 应带 options", ask.optJSONArray("options"));
        assertEquals(2, ask.optJSONArray("options").length());
        loop.answer("对, 就是我记账用");

        assertTrue("澄清后应到达计划闸", waitState(loop, AgentLoop.State.PLAN_GATE));
        JSONObject plan = sink.firstOfType("plan");
        assertNotNull(plan);
        JSONObject u = plan.optJSONObject("understanding");
        assertNotNull("计划事件应带 understanding", u);
        assertEquals("烧烤摊老板", u.optString("user"));

        loop.respondPlan(true, null);
        waitTerminal(loop);
        assertEquals(AgentLoop.State.DONE, loop.getState());
    }

    @Test
    public void revisePlan_cappedAtTwo() throws Exception {
        /* revise_plan 全任务封顶 2 次: 第 3 次被代码拒绝且不挂计划闸
           (2026-07-23 咖啡点单现场: 大脑连出 8 张修订卡烧干 12 步, 零执行) */
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"revise_plan\",\"reason\":\"r1\",\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"y\"}]}",
                "{\"action\":\"revise_plan\",\"reason\":\"r2\",\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"z\"}]}",
                "{\"action\":\"revise_plan\",\"reason\":\"r3\",\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"w\"}]}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeSink sink = new FakeSink();
        AgentLoop loop = startAndApprove("roomRevise", brain, new FakeTools(), sink);
        /* 后台批准器: 按新出现的修订计划卡逐一批准 (初始卡已被 startAndApprove 批准,
           从 1 计起; 不能对同一闸重复 respondPlan — 挂起循环最长 1s 才醒,
           等待期间 state 一直是 PLAN_GATE, 重复响应会被下一闸的复位吞掉, 循环挂死 10 分钟) */
        Thread approver = new Thread(() -> {
            int approved = 1;
            long t0 = System.currentTimeMillis();
            while (loop.isActive() && System.currentTimeMillis() - t0 < 10000) {
                int planCards = 0;
                synchronized (sink) {
                    for (JSONObject l : sink.logs) if ("plan".equals(l.optString("type"))) planCards++;
                }
                if (planCards > approved && loop.getState() == AgentLoop.State.PLAN_GATE) {
                    approved = planCards;
                    loop.respondPlan(true, null);
                }
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        });
        approver.setDaemon(true);
        approver.start();
        /* 修订 3 被拒绝, 不再挂闸, 循环直接走到 finish */
        waitTerminal(loop);
        assertEquals(AgentLoop.State.DONE, loop.getState());
        int planCards = 0;
        for (JSONObject l : sink.logs) if ("plan".equals(l.optString("type"))) planCards++;
        assertEquals("计划卡 = 初始 1 + 修订 2, 第 3 次修订不发卡", 3, planCards);
    }

    // ==================== 内嵌 Linux: shell.exec 闸 ====================

    /** linux 注册表 (shell.exec 已注册) 启动并批准计划 */
    private static AgentLoop startLinuxAndApprove(String roomId, FakeBrain brain,
                                                  FakeTools tools, FakeSink sink) throws Exception {
        java.util.Set<String> paths = new java.util.HashSet<>();
        com.hermes.android.agent.ToolRegistry registry = com.hermes.android.agent.ToolRegistry.build(
                tools, roomId, () -> paths,
                com.hermes.android.agent.ToolRegistry.policyForTest(java.util.Collections.emptySet()),
                true);
        lastSink = sink;
        AgentLoop loop = AgentLoop.startNew(roomId, "测试目标", brain, tools, registry,
                paths, null, sink);
        assertNotNull("应能启动 loop", loop);
        assertTrue("应到达计划闸", waitState(loop, AgentLoop.State.PLAN_GATE));
        loop.respondPlan(true, null);
        return loop;
    }

    @Test
    public void inPlanShell_autoAuthorized() throws Exception {
        /* 计划含 shell.exec: 批准计划即授权, 不弹确认卡直接执行 */
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"shell.exec\",\"desc\":\"看系统\"}]}",
                "{\"action\":\"shell.exec\",\"cmd\":\"uname -a\"}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startLinuxAndApprove("roomS1", brain, tools, sink);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.DONE, loop.getState());
        assertFalse("计划内 shell 不应弹确认卡", sink.hasType("shellPreview"));
        assertTrue("计划内 shell 直接执行", tools.shellCalls.contains("uname -a"));
    }

    @Test
    public void outOfPlanShell_approveRunsOnce() throws Exception {
        /* 计划外首次 shell.exec: 弹确认卡 → 批准 → 执行且本任务不再询问 */
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"shell.exec\",\"cmd\":\"uname -a\",\"timeoutSec\":60}",
                "{\"action\":\"shell.exec\",\"cmd\":\"python3 --version\"}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startLinuxAndApprove("roomS2", brain, tools, sink);
        assertTrue("计划外 shell 应弹确认卡", waitLog(sink, "shellPreview"));
        JSONObject pv = sink.firstOfType("shellPreview");
        assertEquals("uname -a", pv.optString("cmd"));
        assertEquals(60, pv.optInt("timeoutSec"));
        assertTrue("确认前不得执行", tools.shellCalls.isEmpty());
        loop.respondShell(true);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.DONE, loop.getState());
        assertEquals("批准后两条命令都执行 (本任务不再询问)", 2, tools.shellCalls.size());
        int cards = 0;
        for (JSONObject l : sink.logs) if ("shellPreview".equals(l.optString("type"))) cards++;
        assertEquals("确认卡只弹一次", 1, cards);
    }

    @Test
    public void outOfPlanShell_rejectSkips() throws Exception {
        FakeBrain brain = new FakeBrain(
                "{\"plan\":[{\"action\":\"file.write\",\"path\":\"a.html\",\"desc\":\"x\"}]}",
                "{\"action\":\"shell.exec\",\"cmd\":\"rm -rf /\"}",
                "{\"action\":\"finish\",\"summary\":\"完\"}");
        FakeTools tools = new FakeTools();
        FakeSink sink = new FakeSink();

        AgentLoop loop = startLinuxAndApprove("roomS3", brain, tools, sink);
        assertTrue("计划外 shell 应弹确认卡", waitLog(sink, "shellPreview"));
        loop.respondShell(false);
        waitTerminal(loop);

        assertEquals(AgentLoop.State.DONE, loop.getState());
        assertTrue("驳回后不得执行", tools.shellCalls.isEmpty());
        JSONObject step = sink.firstOfType("step");
        assertNotNull(step);
        assertFalse("被驳回的 shell 步骤应标记失败", step.optBoolean("ok"));
        assertTrue(step.optString("result").contains("驳回"));
    }

    @Test
    public void compressText_pinsHeadCutsMiddle() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append("头部第").append(i).append("步内容AAAAAAAAAA\n");
        String head = sb.substring(0, 200);
        sb.setLength(0);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 2000; i++) all.append("步骤").append(i).append(" 输出xxxxxxxxxxxxxxxxxxxx\n");
        String t = all.toString();
        String tail = t.substring(t.length() - 200);

        String c = com.hermes.android.agent.AgentLoop.compressText(head + t);
        assertTrue("头部必须钉住", c.startsWith(head.substring(0, 100)));
        assertTrue("尾部必须保留", c.endsWith(tail));
        assertTrue("必须有压缩标记", c.contains("中段已压缩"));
        assertTrue("总长受控", c.length() <= 16000);
        assertEquals("短文本不压缩", "abc", com.hermes.android.agent.AgentLoop.compressText("abc"));
    }
}
