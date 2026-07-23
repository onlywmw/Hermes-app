package com.hermes.android.agent;

import com.hermes.android.ai.AiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * AgentLoop — council 房 agentic 循环 (DESIGN_AGENT_LOOP v1.1, v1 循环核心)。
 *
 * 状态机: PLANNING → PLAN_GATE → EXECUTING → DONE/FAILED/STOPPED
 * 大脑每轮输出一个 JSON 动作, 原生执行并回灌; 全局单 loop 互斥。
 *
 * 熔断: 单步超时由 AiClient 读超时兜底(60s), 连续 2 次解析失败,
 *       总步数 12, 纯执行时长 10 分钟 (ask_user 挂起暂停计时)。
 * 安全红线: file.write 路径必须在已批准计划内, 计划外硬拒绝。
 */
public class AgentLoop implements Runnable {

    // ==================== 依赖注入 (生产/测试可替换) ====================

    /** 大脑: 给定 system prompt + 用户文本, 返回 LLM 响应 */
    public interface Brain {
        AiClient.AiResponse chat(String systemPrompt, String userText);
    }

    /** 双手: 文件与设备能力 (生产实现走 StorageManager/IntentParser/CapabilityExecutor) */
    public interface Tools {
        /** 房间工作区文件列表 (文本) */
        String fileList(String roomId);
        /** 读房间工作区文件, 成功返回内容, 失败返回以 "ERROR:" 开头的文本 */
        String fileRead(String roomId, String path);
        /** 写房间工作区文件, 返回结果文本 (ok/err) */
        String fileWrite(String roomId, String path, String content);
        /** 仅解析指令 → capability (不执行); 无法解析返回 "" */
        String capabilityOf(String text);
        /** 执行设备指令, 返回结果文本 */
        String deviceCmd(String text);
    }

    /** 工作日志出口: 每条日志一个 JSONObject (type: phase/plan/step/ask/note/deliver/fail/stopped) */
    public interface LogSink {
        void onLog(JSONObject log);
    }

    // ==================== 常量 (熔断与预估) ====================

    public static final int MAX_STEPS = 12;
    public static final int MAX_PARSE_FAILS = 2;
    public static final long MAX_EXEC_MS = 10 * 60_000;
    public static final long ASK_TIMEOUT_MS = 10 * 60_000;
    private static final int MAX_PLAN_CYCLES = 3;
    private static final int READ_TRUNCATE = 2000;
    private static final int EST_TOKENS_PER_STEP = 2500;
    private static final int EST_BASE_TOKENS = 4000;
    private static final int EST_SEC_PER_STEP = 20;

    /** device.cmd 白名单 — 挂在解析后的 capability 上 (不含 file.ls; 文件操作只走 file.*) */
    private static final Set<String> DEVICE_WHITELIST = new HashSet<>(Arrays.asList(
            "battery.status", "system.info", "network.info", "process.list",
            "volume.get", "brightness.get", "wifi.status"));

    // ==================== 全局单 loop 互斥 ====================

    private static AgentLoop current;

    public static synchronized AgentLoop current() { return current; }

    /** 有活跃 loop 返回 null (调用方负责排队/提示) */
    public static synchronized AgentLoop startNew(String roomId, String goal,
                                                  Brain brain, Tools tools, LogSink sink) {
        if (current != null && current.isActive()) return null;
        AgentLoop l = new AgentLoop(roomId, goal, brain, tools, sink);
        current = l;
        new Thread(l, "agent-loop").start();
        return l;
    }

    // ==================== 实例状态 ====================

    private final String loopId;
    private final String roomId;
    private final String goal;
    private final Brain brain;
    private final Tools tools;
    private final LogSink sink;

    private volatile State state = State.PLANNING;
    private volatile boolean stopRequested = false;
    private final Object gateLock = new Object();
    private volatile Boolean planApproved;
    private volatile String planNote;
    private volatile String userAnswer;

    private final Set<String> allowedPaths = new HashSet<>();
    private final StringBuilder transcript = new StringBuilder();
    private final JSONArray producedFiles = new JSONArray();
    private int totalPrompt = 0, totalCompletion = 0;
    private int estTokens = 0, estSeconds = 0;
    private long execStartMs = 0, parkedMs = 0;

    public enum State { PLANNING, PLAN_GATE, EXECUTING, DONE, FAILED, STOPPED }

    private AgentLoop(String roomId, String goal, Brain brain, Tools tools, LogSink sink) {
        this.loopId = "loop" + System.currentTimeMillis();
        this.roomId = roomId;
        this.goal = goal;
        this.brain = brain;
        this.tools = tools;
        this.sink = sink;
    }

    public String getLoopId() { return loopId; }
    public String getRoomId() { return roomId; }
    public State getState() { return state; }
    public boolean isActive() {
        return state == State.PLANNING || state == State.PLAN_GATE || state == State.EXECUTING;
    }

    // ==================== 外部控制 ====================

    public void requestStop() {
        stopRequested = true;
        synchronized (gateLock) { gateLock.notifyAll(); }
    }

    /** ask_user 的答案回灌 */
    public void answer(String text) {
        userAnswer = text;
        synchronized (gateLock) { gateLock.notifyAll(); }
    }

    /** 计划闸: 批准/驳回+补充 */
    public void respondPlan(boolean approved, String note) {
        planApproved = approved;
        planNote = note;
        synchronized (gateLock) { gateLock.notifyAll(); }
    }

    // ==================== 主循环 ====================

    @Override
    public void run() {
        try {
            int planCycles = 0;
            // ---- PLANNING + PLAN_GATE (可经驳回/revise_plan 重入) ----
            while (true) {
                if (planCycles++ >= MAX_PLAN_CYCLES) { fail("计划被驳回次数过多, 已停止"); return; }
                if (stopRequested) { stopped(); return; }
                state = State.PLANNING;
                phase("讨论中");
                JSONArray plan = makePlan();
                if (plan == null) return; // 内部已 fail
                allowedPaths.clear();
                for (int i = 0; i < plan.length(); i++) {
                    JSONObject s = plan.optJSONObject(i);
                    if (s != null && "file.write".equals(s.optString("action"))) {
                        allowedPaths.add(s.optString("path"));
                    }
                }
                estTokens = plan.length() * EST_TOKENS_PER_STEP + EST_BASE_TOKENS;
                estSeconds = plan.length() * EST_SEC_PER_STEP;
                log(new JSONObject()
                        .put("type", "plan").put("loopId", loopId)
                        .put("steps", plan)
                        .put("estTokens", estTokens).put("estSeconds", estSeconds));
                state = State.PLAN_GATE;
                phase("待确认");
                if (!parkForPlan()) { stopped(); return; }
                if (Boolean.TRUE.equals(planApproved)) break;
                // 驳回: 补充回灌, 重新出计划
                transcript.append("【用户驳回计划】").append(planNote != null ? planNote : "").append("\n");
                note("计划已驳回" + (planNote != null && !planNote.isEmpty() ? ": " + planNote : ""));
            }

            // ---- EXECUTING ----
            state = State.EXECUTING;
            phase("执行中");
            execStartMs = System.currentTimeMillis();
            int parseFails = 0;
            for (int step = 1; step <= MAX_STEPS; step++) {
                if (stopRequested) { stopped(); return; }
                if (pureExecMs() > MAX_EXEC_MS) { fail("执行超时 (10 分钟)"); return; }

                AiClient.AiResponse resp = brain.chat(STEP_PROMPT, buildStepInput(step));
                track(resp);
                if (!resp.success) { fail("大脑调用失败: " + resp.content); return; }

                ActionParser.Result r = ActionParser.parse(resp.content);
                if (!r.ok) {
                    parseFails++;
                    if (parseFails >= MAX_PARSE_FAILS) { fail("连续 2 次动作解析失败"); return; }
                    transcript.append("【格式错误】").append(r.err)
                            .append(" — 请只输出一个 JSON 动作。\n");
                    continue;
                }
                parseFails = 0;
                if (!execAction(step, r.action)) return; // 内部已处理 finish/fail/stopped/revise
                if (state != State.EXECUTING) return;    // DONE/FAILED/STOPPED
            }
            fail("步数达上限 (" + MAX_STEPS + "), 任务未完成");
        } catch (Exception e) {
            fail("循环异常: " + e.getMessage());
        }
    }

    /** 执行一个动作; 返回 false 表示循环应结束 (finish/fail/stop/revise 重进计划闸由外层状态体现) */
    private boolean execAction(int step, JSONObject a) {
        String action = a.optString("action");
        long t0 = System.currentTimeMillis();
        switch (action) {
            case "file.list": {
                String res = tools.fileList(roomId);
                stepLog(step, "file.list", "", true, res, t0);
                transcript.append("[file.list] → ").append(oneLine(res)).append("\n");
                return true;
            }
            case "file.read": {
                String path = a.optString("path");
                String res = tools.fileRead(roomId, path);
                boolean ok = !res.startsWith("ERROR:");
                String shown = ok && res.length() > READ_TRUNCATE
                        ? res.substring(0, READ_TRUNCATE) + "\n…(截断)" : res;
                stepLog(step, "file.read", path, ok, shown, t0);
                transcript.append("[file.read ").append(path).append("] → ")
                        .append(oneLine(shown)).append("\n");
                return true;
            }
            case "file.write": {
                String path = a.optString("path");
                String content = a.optString("content");
                // 安全红线: 计划外路径硬拒绝
                if (!allowedPaths.contains(path)) {
                    stepLog(step, "file.write", path, false, "此文件不在批准计划内", t0);
                    transcript.append("[file.write ").append(path)
                            .append("] → 拒绝: 此文件不在批准计划内。如需新增文件, 请输出 revise_plan。\n");
                    return true;
                }
                String res = tools.fileWrite(roomId, path, content);
                boolean ok = res.startsWith("OK:");
                String record = path + " → " + content.length() + " 字符已写入";
                stepLog(step, "file.write", path, ok, ok ? record : res, t0);
                if (ok) producedFiles.put(path);
                // transcript 纪律: content 不进上下文
                transcript.append("[file.write ").append(path).append("] → ").append(record).append("\n");
                return true;
            }
            case "device.cmd": {
                String text = a.optString("text");
                String cap = tools.capabilityOf(text);
                if (!DEVICE_WHITELIST.contains(cap)) {
                    stepLog(step, "device.cmd", text, false,
                            "循环内禁止该能力 (" + (cap.isEmpty() ? "无法解析" : cap) + ")", t0);
                    transcript.append("[device.cmd] → 拒绝: 仅允许只读设备能力, 文件操作请用 file.*\n");
                    return true;
                }
                String res = tools.deviceCmd(text);
                stepLog(step, "device.cmd", text, true, res, t0);
                transcript.append("[device.cmd ").append(text).append("] → ").append(oneLine(res)).append("\n");
                return true;
            }
            case "ask_user": {
                String q = a.optString("question");
                log(j("type", "ask", "loopId", loopId, "question", q));
                String ans = parkForAnswer();
                if (stopRequested) { stopped(); return false; }
                String shown = ans != null ? ans : "(用户未回复)";
                note("你: " + shown);
                transcript.append("[ask_user] Q: ").append(q).append(" A: ").append(shown).append("\n");
                return true;
            }
            case "revise_plan": {
                JSONArray newPlan = a.optJSONArray("plan");
                if (newPlan == null || newPlan.length() == 0) {
                    transcript.append("[revise_plan] → 拒绝: plan 为空\n");
                    return true;
                }
                allowedPaths.clear();
                for (int i = 0; i < newPlan.length(); i++) {
                    JSONObject s = newPlan.optJSONObject(i);
                    if (s != null && "file.write".equals(s.optString("action"))) {
                        allowedPaths.add(s.optString("path"));
                    }
                }
                estTokens = newPlan.length() * EST_TOKENS_PER_STEP + EST_BASE_TOKENS;
                estSeconds = newPlan.length() * EST_SEC_PER_STEP;
                log(j("type", "plan", "loopId", loopId,
                        "steps", newPlan, "revised", true,
                        "estTokens", estTokens, "estSeconds", estSeconds));
                state = State.PLAN_GATE;
                phase("待确认");
                if (!parkForPlan()) { stopped(); return false; }
                if (Boolean.TRUE.equals(planApproved)) {
                    state = State.EXECUTING;
                    phase("执行中");
                    return true;
                }
                transcript.append("【用户驳回修订计划】").append(planNote != null ? planNote : "").append("\n");
                state = State.EXECUTING; // 继续, 大脑自行调整 (驳回计入 transcript)
                phase("执行中");
                return true;
            }
            case "finish": {
                deliver(a.optString("summary"));
                return false;
            }
            default:
                stepLog(step, action, "", false, "未知动作", t0);
                transcript.append("[").append(action).append("] → 未知动作, 请使用协议内动作。\n");
                return true;
        }
    }

    // ==================== 计划 ====================

    private JSONArray makePlan() {
        String input = "用户目标: " + goal + "\n\n房间工作区现有文件:\n" + tools.fileList(roomId)
                + (transcript.length() > 0 ? "\n\n补充信息:\n" + transcript : "");
        for (int attempt = 1; attempt <= MAX_PARSE_FAILS; attempt++) {
            AiClient.AiResponse resp = brain.chat(PLAN_PROMPT, input);
            track(resp);
            if (!resp.success) { fail("大脑调用失败: " + resp.content); return null; }
            try {
                String json = ActionParser.extractJson(resp.content);
                if (json != null) {
                    JSONArray plan = new JSONObject(json).optJSONArray("plan");
                    if (plan != null && plan.length() > 0) return plan;
                }
                input += "\n上次输出格式不对, 请只输出 {\"plan\":[...]} JSON。";
            } catch (Exception e) {
                input += "\n上次输出格式不对, 请只输出 {\"plan\":[...]} JSON。";
            }
        }
        fail("连续 2 次计划解析失败");
        return null;
    }

    private String buildStepInput(int step) {
        String t = transcript.toString();
        // 压缩: 超 8k 保留尾部
        if (t.length() > 8000) t = "…(早期步骤已压缩)\n" + t.substring(t.length() - 7000);
        return "目标: " + goal + "\n已批准的可写文件: " + allowedPaths
                + "\n\n工作日志:\n" + t
                + "\n\n这是第 " + step + "/" + MAX_STEPS + " 步。只输出一个 JSON 动作。";
    }

    // ==================== 暂停 (挂起暂停总时长计时) ====================

    private boolean parkForPlan() {
        planApproved = null; planNote = null;
        synchronized (gateLock) {
            while (planApproved == null && !stopRequested) {
                try { gateLock.wait(1000); } catch (InterruptedException e) { return false; }
            }
        }
        return !stopRequested;
    }

    private String parkForAnswer() {
        userAnswer = null;
        long t0 = System.currentTimeMillis();
        synchronized (gateLock) {
            while (userAnswer == null && !stopRequested) {
                long left = ASK_TIMEOUT_MS - (System.currentTimeMillis() - t0);
                if (left <= 0) break;
                try { gateLock.wait(Math.min(left, 1000)); } catch (InterruptedException e) { break; }
            }
        }
        parkedMs += System.currentTimeMillis() - t0;
        String a = userAnswer; userAnswer = null;
        return a;
    }

    private long pureExecMs() {
        return System.currentTimeMillis() - execStartMs - parkedMs;
    }

    // ==================== 日志与收尾 ====================

    /** 安全 JSON 构造 (org.json put 抛受检异常, 统一吞掉) */
    private static JSONObject j(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
        } catch (Exception ignored) {}
        return o;
    }

    private void log(JSONObject j) {
        try { sink.onLog(j); } catch (Exception ignored) {}
    }

    private void phase(String p) {
        log(j("type", "phase", "loopId", loopId, "phase", p));
    }

    private void note(String text) {
        log(j("type", "note", "loopId", loopId, "text", text));
    }

    private void stepLog(int seq, String name, String arg, boolean ok, String result, long t0) {
        long dur = System.currentTimeMillis() - t0;
        log(j("type", "step", "loopId", loopId,
                "seq", seq, "name", name, "arg", arg, "ok", ok,
                "result", result, "durMs", dur,
                "promptTokens", totalPrompt, "completionTokens", totalCompletion,
                "elapsedSec", pureExecMs() / 1000));
    }

    private void deliver(String summary) {
        state = State.DONE;
        phase("已交付");
        log(j("type", "deliver", "loopId", loopId,
                "summary", summary, "files", producedFiles,
                "promptTokens", totalPrompt, "completionTokens", totalCompletion,
                "elapsedSec", pureExecMs() / 1000,
                "estTokens", estTokens, "estSeconds", estSeconds));
    }

    private void fail(String reason) {
        state = State.FAILED;
        phase("失败");
        log(j("type", "fail", "loopId", loopId, "reason", reason,
                "promptTokens", totalPrompt, "completionTokens", totalCompletion,
                "elapsedSec", pureExecMs() / 1000));
    }

    private void stopped() {
        state = State.STOPPED;
        phase("已停止");
        log(j("type", "stopped", "loopId", loopId,
                "promptTokens", totalPrompt, "completionTokens", totalCompletion,
                "elapsedSec", pureExecMs() / 1000));
    }

    private void track(AiClient.AiResponse resp) {
        if (resp != null) {
            totalPrompt += Math.max(0, resp.promptTokens);
            totalCompletion += Math.max(0, resp.completionTokens);
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String l = s.replace('\n', ' ');
        return l.length() > 120 ? l.substring(0, 120) + "…" : l;
    }

    // ==================== Prompt ====================

    private static final String PLAN_PROMPT =
            "你是 MOV agent 的大脑, 运行在一个 Android 房间的 agentic 循环里。"
            + "把用户目标拆成执行计划, 只输出 JSON, 不要输出其他任何文字:\n"
            + "{\"plan\":[{\"action\":\"file.write\",\"path\":\"文件名\",\"desc\":\"这一步做什么\"}]}\n"
            + "规则: file.write 的 path 是你计划创建/修改的文件, 后续执行只允许写这些文件;"
            + "小游戏/网页类需求规划为一个可运行的单文件 HTML; 不规划图片等二进制文件。";

    private static final String STEP_PROMPT =
            "你是 MOV agent 的大脑, 正在驱动一个 agentic 循环。规则:\n"
            + "1. 每轮只输出一个 JSON 动作, 不要输出其他文字。\n"
            + "2. 可用动作: {\"action\":\"file.list\"} | {\"action\":\"file.read\",\"path\":\"f\"} | "
            + "{\"action\":\"file.write\",\"path\":\"f\",\"content\":\"完整内容\"} | "
            + "{\"action\":\"device.cmd\",\"text\":\"只读设备指令\"} | "
            + "{\"action\":\"ask_user\",\"question\":\"问用户的问题\"} | "
            + "{\"action\":\"revise_plan\",\"reason\":\"原因\",\"plan\":[...]} | "
            + "{\"action\":\"finish\",\"summary\":\"交付说明\"}\n"
            + "3. file.write 只能写已批准计划中的文件, content 必须是完整最终内容。\n"
            + "4. 工具结果在工作日志里, 根据结果决定下一步; 需要回看文件内容用 file.read。\n"
            + "5. 目标完成就输出 finish。";
}
