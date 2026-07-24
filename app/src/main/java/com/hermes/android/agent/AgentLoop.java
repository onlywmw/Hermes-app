package com.hermes.android.agent;

import com.hermes.android.ai.AiClient;

import org.json.JSONArray;
import org.json.JSONObject;

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

    /** 双手: 文件与设备能力 (生产实现走 StorageManager/IntentParser/CapabilityExecutor/PackageBuilder) */
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
        /** HTML → 签名 APK 并落到房间工作区; 成功 JSON {"ok":true,"file":..,"msg":..},
         *  失败 {"ok":false,"error":..} (旧实现 "OK: <file> · ..." / "ERR: ..." 亦兼容) */
        String packageApk(String roomId, String path, String appName);
        /** 内嵌 Linux 执行 shell 命令, 返回 "exit=N\n<stdout>\n<stderr>" 文本;
         *  rootfs 未就绪返回以 "ERROR:" 开头的引导文本 */
        String shellExec(String cmd, int timeoutSec);
        /** 房间工作区文件 → Linux /exchange, 返回 JSON {ok,..} */
        String filePush(String roomId, String path);
        /** Linux /exchange 产物 → 房间工作区, 返回 JSON {ok,..} */
        String filePull(String roomId, String name);
    }

    /** 工作日志出口: 每条日志一个 JSONObject (type: phase/plan/step/ask/note/review/deliver/fail/stopped) */
    public interface LogSink {
        void onLog(JSONObject log);
    }

    /**
     * 评审团 (v2, 可空 → 跳过两个评审点)。
     * 返回 JSONObject 约定: 含 pt/ct (本次评审 token 消耗);
     * planReview 含 items: [{name,role,comment}];
     * deliveryVote 含 pass/fail/comments: [{name,pass,reason}], notReviewed: bool
     * (productsDigest=产物内容摘要, 评审据此证伪; 调用失败不计票; 无评审模型→notReviewed)。
     */
    public interface Reviewer {
        JSONObject planReview(String goal, JSONArray plan);
        JSONObject deliveryVote(String goal, String logDigest, JSONArray files, String productsDigest);
    }

    // ==================== 常量 (熔断与预估) ====================

    public static final int MAX_STEPS = 12;
    public static final int REWORK_STEPS = 6;
    public static final int MAX_REWORK_ROUNDS = 2;
    public static final int MAX_PARSE_FAILS = 2;
    public static final int MAX_REVISE_PLANS = 2;   // revise_plan 全任务封顶 (防规划死循环)
    public static final long MAX_EXEC_MS = 10 * 60_000;
    public static final long ASK_TIMEOUT_MS = 10 * 60_000;
    /** 计划闸超时: 用户长时间不批准 → 自动停止, 防僵尸 loop 堵死全局队列 */
    public static final long PLAN_GATE_TIMEOUT_MS = 10 * 60_000;
    private static final int MAX_PLAN_CYCLES = 3;
    private static final int EST_TOKENS_PER_STEP = 2500;
    private static final int EST_BASE_TOKENS = 4000;
    private static final int EST_SEC_PER_STEP = 20;

    // ==================== 全局单 loop 互斥 ====================

    private static AgentLoop current;

    public static synchronized AgentLoop current() { return current; }

    /** 有活跃 loop 返回 null (调用方负责排队/提示); reviewer 可空 (跳过评审点)
     *  allowedPaths 由组装方创建并共享给注册表 (revise_plan 时循环写入, 注册表即时可见) */
    public static synchronized AgentLoop startNew(String roomId, String goal,
                                                  Brain brain, Tools tools,
                                                  ToolRegistry registry,
                                                  Set<String> allowedPaths,
                                                  Reviewer reviewer, LogSink sink) {
        if (current != null && current.isActive()) return null;
        AgentLoop l = new AgentLoop(roomId, goal, brain, tools, registry,
                allowedPaths != null ? allowedPaths : new HashSet<>(), reviewer, sink);
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
    private final ToolRegistry registry;   // 工具唯一入口: agent 与硬件控制的分割层
    private final Reviewer reviewer; // 可空 → 跳过评审点
    private final LogSink sink;
    private final String stepPrompt;   // 由注册表动态生成 (工具随设备而变)
    private final String planPrompt;   // 同上: 规划阶段就知道本机能力

    private volatile State state = State.PLANNING;
    private volatile boolean stopRequested = false;
    private final Object gateLock = new Object();
    private volatile Boolean planApproved;
    private volatile String planNote;
    private volatile String userAnswer;
    private volatile Boolean fileWriteApproved;
    private volatile Boolean shellApproved;
    private boolean allowedShell = false;   // 计划含 shell.exec 或用户批准过一次 → 本任务放行

    private final Set<String> allowedPaths;   // 组装方共享 (注册表 file.write 直接读它)
    private final StringBuilder transcript = new StringBuilder();
    private final JSONArray producedFiles = new JSONArray();
    private int stepsDone = 0;   // 已完成步数 (失败三问: 部分产物进度)
    private int totalPrompt = 0, totalCompletion = 0;
    private int reviewPt = 0, reviewCt = 0;   // v2: 评审消耗单独累计
    private int estTokens = 0, estSeconds = 0;
    private long execStartMs = 0, parkedMs = 0;
    private int stepCounter = 0;
    private int reviseCount = 0;   // revise_plan 计数 (封顶 MAX_REVISE_PLANS)
    private String finishSummary = null;

    public enum State { PLANNING, PLAN_GATE, EXECUTING, DONE, FAILED, STOPPED }

    private AgentLoop(String roomId, String goal, Brain brain, Tools tools,
                      ToolRegistry registry, Set<String> allowedPaths,
                      Reviewer reviewer, LogSink sink) {
        this.loopId = "loop" + System.currentTimeMillis();
        this.roomId = roomId;
        this.goal = goal;
        this.brain = brain;
        this.tools = tools;
        this.registry = registry;
        this.allowedPaths = allowedPaths;
        this.reviewer = reviewer;
        this.sink = sink;
        // 工具说明由注册表生成: 换设备/换工具, prompt 自动跟随
        this.stepPrompt = STEP_RULES + "\n工具协议:\n" + registry.promptText();
        this.planPrompt = PLAN_RULES + "\n可用手段:\n" + registry.promptText();
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

    /** P1 文件写入预览闸: 确认/驳回 (与计划闸共用唤醒锁) */
    public void respondFileWrite(boolean approved) {
        fileWriteApproved = approved;
        synchronized (gateLock) { gateLock.notifyAll(); }
    }

    /** 计划外 shell.exec 确认闸: 批准即本任务放行全部 shell.exec (照 respondFileWrite 模式) */
    public void respondShell(boolean approved) {
        shellApproved = approved;
        synchronized (gateLock) { gateLock.notifyAll(); }
    }

    // ==================== 主循环 ====================

    @Override
    public void run() {
        execStartMs = System.currentTimeMillis(); // PLANNING 阶段 fail/stopped 也需要有效起点
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
                allowedShell = false;
                for (int i = 0; i < plan.length(); i++) {
                    JSONObject s = plan.optJSONObject(i);
                    if (s != null && "file.write".equals(s.optString("action"))) {
                        allowedPaths.add(s.optString("path"));
                    }
                    if (s != null && "shell.exec".equals(s.optString("action"))) {
                        allowedShell = true;   // 批准含 shell.exec 的计划 = 授权本任务 shell
                    }
                }
                estTokens = plan.length() * EST_TOKENS_PER_STEP + EST_BASE_TOKENS;
                estSeconds = plan.length() * EST_SEC_PER_STEP;
                // v2 PLAN_REVIEW: 评审团点评附进计划卡 (不占循环步数)
                JSONArray planReviews = doPlanReview(plan);
                JSONObject planLog = j("type", "plan", "loopId", loopId,
                        "steps", plan, "estTokens", estTokens, "estSeconds", estSeconds);
                if (planUnderstanding != null) {
                    try { planLog.put("understanding", planUnderstanding); } catch (Exception ignored) {}
                }
                if (planReviews != null) {
                    try { planLog.put("reviews", planReviews); } catch (Exception ignored) {}
                }
                log(planLog);
                planApproved = null; planNote = null; // 闸前复位 (先于开闸, 防批准被 park 窗口吞掉)
                state = State.PLAN_GATE;
                phase("待确认");
                if (!parkForPlan()) { stopped(); return; }
                if (Boolean.TRUE.equals(planApproved)) { approvedPlanText = formatPlan(plan); break; }
                // 驳回: 补充回灌, 重新出计划
                transcript.append("【用户驳回计划】").append(planNote != null ? planNote : "").append("\n");
                note("计划已驳回" + (planNote != null && !planNote.isEmpty() ? ": " + planNote : ""));
            }

            // ---- EXECUTING (含 v2 交付评审返工轮) ----
            state = State.EXECUTING;
            phase("执行中");
            String summary = executeSteps(MAX_STEPS);
            if (summary == null) return; // fail/stopped 已处理

            // ---- v2 DELIV_REVIEW (评审看产物内容; 失败不计票; 未评审明示) ----
            if (reviewer == null) { deliver(summary, null, 0, "not_reviewed"); return; }
            String productsDigest = buildProductsDigest();
            for (int round = 1; round <= MAX_REWORK_ROUNDS + 1; round++) {
                JSONObject vote = reviewer.deliveryVote(goal, digest(), producedFiles, productsDigest);
                trackReview(vote);
                int pass = vote != null ? vote.optInt("pass") : 0;
                int failn = vote != null ? vote.optInt("fail") : 0;
                boolean notReviewed = vote == null || vote.optBoolean("notReviewed")
                        || (pass + failn == 0);
                log(j("type", "review", "loopId", loopId, "stage", "deliver",
                        "round", round, "pass", pass, "fail", failn,
                        "notReviewed", notReviewed,
                        "comments", vote != null ? vote.optJSONArray("comments") : null));
                if (notReviewed) { deliver(summary, vote, round - 1, "not_reviewed"); return; }
                if (failn <= pass) {
                    deliver(summary, vote, round - 1, round > 1 ? "reworked" : "passed");
                    return;
                }
                if (round > MAX_REWORK_ROUNDS) {
                    deliver(summary, vote, round - 1, "reworked");
                    return;
                }
                // 返工: 意见回灌, 独立 6 步预算
                String feedback = voteComments(vote);
                transcript.append("【交付评审返工·第").append(round).append("轮】").append(feedback).append("\n");
                note("交付评审: 需返工 (" + pass + " 通过 / " + failn + " 返工), 第 " + round + " 轮修复");
                state = State.EXECUTING;
                phase("执行中");
                summary = executeSteps(REWORK_STEPS);
                if (summary == null) return;
                productsDigest = buildProductsDigest();   // 返工后产物变了, 评审要看新内容
            }
        } catch (Exception e) {
            fail("循环异常: " + e.getMessage());
        }
    }

    /** v2 计划评审; reviewer 为空/评审异常/计划 ≤2 步 (小事不开会) 返回 null (静默跳过) */
    private JSONArray doPlanReview(JSONArray plan) {
        if (reviewer == null || plan.length() <= 2) return null;
        try {
            JSONObject r = reviewer.planReview(goal, plan);
            trackReview(r);
            return r != null ? r.optJSONArray("items") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 执行循环主体: 跑预算内步数, 返回 finish 的 summary; fail/stop/耗尽返回 null */
    private String executeSteps(int budget) {
        finishSummary = null;
        int parseFails = 0;
        int left = budget;
        while (left > 0) {
            if (stopRequested) { stopped(); return null; }
            if (pureExecMs() > MAX_EXEC_MS) { fail("执行超时 (10 分钟)"); return null; }

            stepCounter++;
            AiClient.AiResponse resp = chatWithRetry(stepPrompt, buildStepInput(stepCounter));
            track(resp);
            if (!resp.success) { fail("大脑调用失败: " + resp.content); return null; }

            ActionParser.Result r = ActionParser.parse(resp.content);
            if (!r.ok) {
                parseFails++;
                if (parseFails >= MAX_PARSE_FAILS) { fail("连续 2 次动作解析失败"); return null; }
                transcript.append("【格式错误】").append(r.err)
                        .append(" — 请只输出一个 JSON 动作。\n");
                continue;
            }
            parseFails = 0;
            left--; // 预算只消耗在合法动作上
            if (!execAction(stepCounter, r.action)) return finishSummary; // finish→summary; fail/stop→null
            stepsDone++;
        }
        fail("步数达上限 (" + budget + "), 任务未完成");
        return null;
    }

    /** 执行一个动作; 返回 false 表示循环应结束 (finish/fail/stop/revise 重进计划闸由外层状态体现) */
    private boolean execAction(int step, JSONObject a) {
        String action = a.optString("action");
        long t0 = System.currentTimeMillis();
        switch (action) {
            case "ask_user": {
                String q = a.optString("question");
                JSONObject askEv = j("type", "ask", "loopId", loopId, "question", q);
                JSONArray opts = a.optJSONArray("options");
                if (opts != null) {
                    try { askEv.put("options", opts); } catch (Exception ignored) {}
                }
                userAnswer = null;   // 闸前复位 (同计划闸, 防答案被 park 窗口吞掉)
                log(askEv);
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
                /* 修订封顶: 大脑会把 revise_plan 当"再想一下"按钮连按, 烧干步数预算零执行
                   (2026-07-23 咖啡点单现场: 8 张修订卡烧掉 12 步 + 40k tokens) */
                if (reviseCount >= MAX_REVISE_PLANS) {
                    transcript.append("[revise_plan] → 拒绝: 修订次数已用完 (上限 ")
                            .append(MAX_REVISE_PLANS)
                            .append("), 请按当前已批准计划执行, 或输出 finish。\n");
                    return true;
                }
                reviseCount++;
                allowedPaths.clear();
                for (int i = 0; i < newPlan.length(); i++) {
                    JSONObject s = newPlan.optJSONObject(i);
                    if (s != null && "file.write".equals(s.optString("action"))) {
                        allowedPaths.add(s.optString("path"));
                    }
                    if (s != null && "shell.exec".equals(s.optString("action"))) {
                        allowedShell = true;   // 修订计划含 shell.exec 且被批准 → 放行
                    }
                }
                estTokens = newPlan.length() * EST_TOKENS_PER_STEP + EST_BASE_TOKENS;
                estSeconds = newPlan.length() * EST_SEC_PER_STEP;
                log(j("type", "plan", "loopId", loopId,
                        "steps", newPlan, "revised", true,
                        "estTokens", estTokens, "estSeconds", estSeconds));
                planApproved = null; planNote = null; // 闸前复位 (同初始闸, 防批准被吞)
                state = State.PLAN_GATE;
                phase("待确认");
                if (!parkForPlan()) { stopped(); return false; }
                if (Boolean.TRUE.equals(planApproved)) {
                    approvedPlanText = formatPlan(newPlan);
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
                finishSummary = a.optString("summary");
                return false;
            }
            default: {
                /* 通用执行器: 查注册表执行 (agent 不认识任何具体工具) */
                ToolRegistry.Tool tool = registry.find(action);
                if (tool == null) {
                    stepLog(step, action, "", false, "没有此工具或未放行", t0);
                    transcript.append("[").append(action)
                            .append("] → 没有此工具或未放行, 请使用协议内动作。\n");
                    return true;
                }
                String arg = a.optString("path", a.optString("text", ""));
                /* 闸门精简: 计划内写入 = 批准计划时已授权, 直接执行;
                   计划外写入 = 唯一的写入预览闸 (用户批准即临时授权该路径) */
                if ("file.write".equals(action) && !allowedPaths.contains(arg)) {
                    fileWriteApproved = null;   // 闸前复位
                    log(j("type", "filePreview", "loopId", loopId,
                            "path", arg, "content", a.optString("content"), "outOfPlan", true));
                    boolean writeOk = parkForFileWrite();
                    if (stopRequested) { stopped(); return false; }
                    if (!writeOk) {
                        stepLog(step, action, arg, false, "计划外写入被用户驳回", t0);
                        transcript.append("[").append(action).append(" ").append(arg)
                                .append("] → 计划外写入被驳回, 请只用计划内文件或 revise_plan。\n");
                        return true;
                    }
                    allowedPaths.add(arg);   // 用户确认 = 授权该路径
                }
                /* shell.exec 闸 (照 filePreview 模式): 计划批准/revise 含 shell.exec 即 allowedShell;
                   计划外首次 → 预览完整命令 + 用户确认, 批准 = 本任务放行全部 shell.exec */
                if ("shell.exec".equals(action) && !allowedShell) {
                    shellApproved = null;   // 闸前复位
                    log(j("type", "shellPreview", "loopId", loopId,
                            "cmd", a.optString("cmd"),
                            "timeoutSec", a.optInt("timeoutSec", 120)));
                    boolean shellOk = parkForShell();
                    if (stopRequested) { stopped(); return false; }
                    if (!shellOk) {
                        stepLog(step, action, "", false, "计划外 shell 命令被用户驳回", t0);
                        transcript.append("[shell.exec] → 计划外 shell 命令被驳回,"
                                + " 请改用其他工具或 revise_plan 把 shell.exec 写进计划。\n");
                        return true;
                    }
                    allowedShell = true;   // 用户确认 = 本任务放行
                }
                try {
                    ToolRegistry.Result res = tool.handler.run(a);
                    stepLog(step, action, arg, res.ok, res.text, t0);
                    if (res.produced != null) producedFiles.put(res.produced);
                    /* file.read 的全文必须进 transcript — oneLine 截 120 字符会让大脑
                       永远看不到自己读到的内容, 只能反复重读 (R4 复读循环的根因);
                       大文件由 transcript 16k 压缩窗兜底 */
                    String forTranscript = "file.read".equals(action) ? res.text : oneLine(res.text);
                    transcript.append("[").append(action).append(arg.isEmpty() ? "" : " " + arg)
                            .append("] → ").append(forTranscript).append("\n");
                } catch (Exception e) {
                    stepLog(step, action, arg, false, "执行异常: " + e.getMessage(), t0);
                    transcript.append("[").append(action).append("] → 执行异常: ")
                            .append(oneLine(e.getMessage())).append("\n");
                }
                return true;
            }
        }
    }

    // ==================== 计划 ====================

    private JSONObject planUnderstanding;   // 需求理解 (计划卡展示, 批准计划=批准理解)
    private String approvedPlanText;        // 已批准计划文本 (压缩时钉住不丢)
    private boolean compressNoted;          // 上下文压缩提示只发一次

    private JSONArray makePlan() {
        String input = "用户目标: " + goal + "\n\n房间工作区现有文件:\n" + tools.fileList(roomId)
                + (transcript.length() > 0 ? "\n\n补充信息:\n" + transcript : "");
        planUnderstanding = null;
        int clarifies = 0;
        for (int attempt = 1; attempt <= MAX_PARSE_FAILS; attempt++) {
            AiClient.AiResponse resp = chatWithRetry(planPrompt, input);
            track(resp);
            if (!resp.success) { fail("大脑调用失败: " + resp.content); return null; }
            try {
                String json = ActionParser.extractJson(resp.content);
                if (json != null) {
                    JSONObject root = new JSONObject(json);
                    /* 理解闸: 场景有多种合理解读时, 大脑先回显澄清再出计划 */
                    String clarify = root.optString("clarify", "");
                    JSONArray clarifyOpts = null;
                    JSONObject clarifyObj = root.optJSONObject("clarify");
                    if (clarifyObj != null) {
                        clarify = clarifyObj.optString("question", clarify);
                        clarifyOpts = clarifyObj.optJSONArray("options");
                    }
                    if (!clarify.isEmpty() && clarifies < 2) {
                        clarifies++;
                        JSONObject askEv = j("type", "ask", "loopId", loopId, "question", clarify);
                        if (clarifyOpts != null) {
                            try { askEv.put("options", clarifyOpts); } catch (Exception ignored) {}
                        }
                        userAnswer = null;   // 闸前复位
                        log(askEv);
                        String ans = parkForAnswer();
                        if (stopRequested) { stopped(); return null; }
                        input += "\n用户澄清: " + (ans != null ? ans : "(用户未回复, 按你最合理的理解直接出计划)");
                        attempt--;   // 澄清不消耗解析重试次数
                        continue;
                    }
                    JSONArray plan = root.optJSONArray("plan");
                    if (plan != null && plan.length() > 0) {
                        planUnderstanding = root.optJSONObject("understanding");
                        return plan;
                    }
                }
                input += "\n上次输出格式不对, 请只输出 {\"understanding\":{...},\"plan\":[...]} JSON。";
            } catch (Exception e) {
                input += "\n上次输出格式不对, 请只输出 {\"understanding\":{...},\"plan\":[...]} JSON。";
            }
        }
        fail("连续 2 次计划解析失败");
        return null;
    }

    private String buildStepInput(int step) {
        String t = compressTranscript(transcript.toString());
        return "目标: " + goal + "\n已批准的可写文件: " + allowedPaths
                + (approvedPlanText != null ? "\n已批准计划:\n" + approvedPlanText : "")
                + "\n\n工作日志:\n" + t
                + "\n\n这是第 " + step + "/" + MAX_STEPS + " 步。只输出一个 JSON 动作。";
    }

    /** 大脑调用 + 一次网络重试 (手机网络抖动是常态, 单次失败不该判任务死刑);
       配置错误(未配置/key 无效)不重试 — 重试只会写两条误导性日志 */
    private AiClient.AiResponse chatWithRetry(String systemPrompt, String input) {
        AiClient.AiResponse resp = brain.chat(systemPrompt, input);
        if (resp.success || stopRequested || !isTransientNetError(resp.content)) return resp;
        note("网络抖动, 2 秒后重试大脑调用… (" + oneLine(resp.content) + ")");
        parkedMs += 2000; // 重试等待不计入纯执行时长
        try { Thread.sleep(2000); } catch (InterruptedException e) { return resp; }
        return brain.chat(systemPrompt, input);
    }

    private static boolean isTransientNetError(String err) {
        if (err == null) return false;
        return err.contains("超时") || err.contains("abort") || err.contains("Socket")
                || err.contains("onnection") || err.contains("网络") || err.contains("timed out");
    }

    /** 压缩: 超 16k 钉住头部(早期上下文)截中段 — 旧版只留尾部, 计划/已读文件会蒸发 */
    private String compressTranscript(String t) {
        if (t.length() <= 16000) return t;
        if (!compressNoted) { compressNoted = true; note("🗜️ 上下文已压缩: 保留了开头和最近进展, 中间步骤省略"); }
        return compressText(t);
    }

    /** 纯函数版压缩 (测试用): 头 1500 + 尾 13000 */
    public static String compressText(String t) {
        if (t.length() <= 16000) return t;
        return t.substring(0, 1500) + "\n…(中段已压缩, 头部保留)\n" + t.substring(t.length() - 13000);
    }

    /** 计划格式化为钉住文本 (压缩时随每步提示大脑, 防"忘了计划") */
    private static String formatPlan(JSONArray plan) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.length(); i++) {
            JSONObject s = plan.optJSONObject(i);
            if (s == null) continue;
            sb.append(i + 1).append(". ").append(s.optString("action"));
            String path = s.optString("path");
            if (!path.isEmpty()) sb.append(" ").append(path);
            String desc = s.optString("desc");
            if (!desc.isEmpty()) sb.append(" — ").append(desc);
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 暂停 (挂起暂停总时长计时) ====================

    private boolean parkForPlan() {
        /* 注意: planApproved/planNote 的复位必须在 state=PLAN_GATE 之前由调用方完成 —
           在这里复位会吞掉"闸已开但 park 尚未进入"窗口内到达的批准 (真机等 10 分钟超时) */
        long t0 = System.currentTimeMillis();
        boolean ok = false;
        synchronized (gateLock) {
            while (planApproved == null && !stopRequested) {
                if (System.currentTimeMillis() - t0 > PLAN_GATE_TIMEOUT_MS) {
                    note("计划超时未批准, 自动停止");
                    break;
                }
                try { gateLock.wait(1000); } catch (InterruptedException e) { break; }
            }
            ok = planApproved != null && !stopRequested;
        }
        parkedMs += System.currentTimeMillis() - t0; // 计划闸停留与 ask_user 一样不计入纯执行时长
        return ok;
    }

    /** P1: 写入预览挂起 (复用计划闸 wait/notify 模式); true=用户确认写入 */
    private boolean parkForFileWrite() {
        /* 注意: fileWriteApproved 的复位必须在 filePreview 事件发出前由调用方完成 */
        long t0 = System.currentTimeMillis();
        synchronized (gateLock) {
            while (fileWriteApproved == null && !stopRequested) {
                if (System.currentTimeMillis() - t0 > PLAN_GATE_TIMEOUT_MS) {
                    note("写入确认超时, 视为驳回");
                    break;
                }
                try { gateLock.wait(1000); } catch (InterruptedException e) { break; }
            }
        }
        parkedMs += System.currentTimeMillis() - t0; // 与计划闸一样不计入纯执行时长
        return Boolean.TRUE.equals(fileWriteApproved);
    }

    /** 计划外 shell.exec 确认挂起 (复用 fileWrite 闸 wait/notify 模式); true=用户批准 */
    private boolean parkForShell() {
        /* 注意: shellApproved 的复位必须在 shellPreview 事件发出前由调用方完成 */
        long t0 = System.currentTimeMillis();
        synchronized (gateLock) {
            while (shellApproved == null && !stopRequested) {
                if (System.currentTimeMillis() - t0 > PLAN_GATE_TIMEOUT_MS) {
                    note("shell 确认超时, 视为驳回");
                    break;
                }
                try { gateLock.wait(1000); } catch (InterruptedException e) { break; }
            }
        }
        parkedMs += System.currentTimeMillis() - t0;
        return Boolean.TRUE.equals(shellApproved);
    }

    private String parkForAnswer() {
        /* 注意: userAnswer 的复位必须在 ask 事件发出前由调用方完成 (同计划闸) */
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

    private void deliver(String summary, JSONObject vote, int reworkRounds, String reviewState) {
        phase("已交付");
        JSONObject d = j("type", "deliver", "loopId", loopId,
                "summary", summary, "files", producedFiles,
                "promptTokens", totalPrompt, "completionTokens", totalCompletion,
                "reviewTokens", reviewPt + reviewCt,
                "reworkRounds", reworkRounds,
                "reviewState", reviewState,   // passed | reworked | not_reviewed
                "elapsedSec", pureExecMs() / 1000,
                "estTokens", estTokens, "estSeconds", estSeconds);
        try {
            /* 功能自检清单 (M-QUALITY: finish summary 的 ✅/⚠️ 行, 供交付卡结构化展示) */
            org.json.JSONArray checklist = new org.json.JSONArray();
            for (ProductDigest.CheckItem item : ProductDigest.parseChecklist(summary)) {
                checklist.put(new JSONObject().put("real", item.real).put("text", item.text));
            }
            d.put("checklist", checklist);
        } catch (Exception ignored) {}
        if (vote != null) {
            try {
                d.put("pass", vote.optInt("pass")).put("failVotes", vote.optInt("fail"))
                 .put("comments", vote.optJSONArray("comments"));
            } catch (Exception ignored) {}
        }
        log(d);
        /* 交付事件落盘后再置终态 — 否则观察者(测试/UI)会在 deliver 事件前读到 DONE */
        state = State.DONE;
    }

    /** 交付评审用的产物内容摘要: 文本产物头 4000 字符 + HTML 交互元素清单 */
    private String buildProductsDigest() {
        java.util.Map<String, String> contents = new java.util.LinkedHashMap<>();
        for (int i = 0; i < producedFiles.length(); i++) {
            String name = producedFiles.optString(i);
            if (!ProductDigest.isTextFile(name)) continue;
            try {
                contents.put(name, tools.fileRead(roomId, name));
            } catch (Exception e) {
                contents.put(name, "(读取失败: " + e.getMessage() + ")");
            }
        }
        return ProductDigest.build(contents);
    }

    /** v2: 评审 token 单独累计 */
    private void trackReview(JSONObject reviewResult) {
        if (reviewResult != null) {
            reviewPt += Math.max(0, reviewResult.optInt("pt"));
            reviewCt += Math.max(0, reviewResult.optInt("ct"));
        }
    }

    /** 交付评审用的日志摘要 (尾部 1500 字符 + 文件清单) */
    private String digest() {
        String t = transcript.toString();
        if (t.length() > 1500) t = "…\n" + t.substring(t.length() - 1500);
        return "目标: " + goal + "\n产出文件: " + producedFiles.toString() + "\n工作日志(尾部):\n" + t;
    }

    private String voteComments(JSONObject vote) {
        if (vote == null) return "";
        JSONArray comments = vote.optJSONArray("comments");
        if (comments == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < comments.length(); i++) {
            JSONObject c = comments.optJSONObject(i);
            if (c != null && !c.optBoolean("pass", true)) {
                sb.append(c.optString("name")).append(": ").append(c.optString("reason")).append("; ");
            }
        }
        return sb.toString();
    }

    private void fail(String reason) {
        state = State.FAILED;
        phase("失败");
        /* 失败三问: 人话原因 + 部分产物(files) + 进度(stepsDone) + 原目标(goal, 供一键重开) */
        log(j("type", "fail", "loopId", loopId, "reason", reason,
                "files", producedFiles, "stepsDone", stepsDone, "goal", goal,
                "promptTokens", totalPrompt, "completionTokens", totalCompletion,
                "elapsedSec", pureExecMs() / 1000));
    }

    private void stopped() {
        state = State.STOPPED;
        phase("已停止");
        log(j("type", "stopped", "loopId", loopId,
                "files", producedFiles, "stepsDone", stepsDone,
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

    private static final String PLAN_RULES =
            "你是 MOV agent 的大脑, 运行在一个 Android 房间的 agentic 循环里。"
            + "把用户目标拆成执行计划, 只输出 JSON, 不要输出其他任何文字:\n"
            + "{\"understanding\":{\"user\":\"给谁用\",\"scenario\":\"什么场景下用\",\"loop\":\"核心闭环(一句话)\",\"guess\":\"你猜的,可能错的部分\"},\"plan\":[{\"action\":\"file.write\",\"path\":\"文件名\",\"desc\":\"这一步做什么\"}]}\n"
            + "规则: file.write 的 path 是你计划创建/修改的文件, 后续执行只允许写这些文件;"
            + "计划必须完整覆盖到交付 — 修改类任务 = 读取(如需要)+file.write(每个要改的文件都有步)+"
            + "app.package(需要 APK 时), 禁止只规划读取/调查步骤;"
            + "小游戏/网页类需求规划为一个可运行的单文件 HTML; 不规划图片等二进制文件;"
            + "工作区里的 .apk 都由同名 .html 打包而来 — 用户要求改游戏/改应用时,"
            + "计划 = 修改源 .html + 重新 app.package, 不要问用户玩的是 html 还是 apk;"
            + "涉及手机硬件操作(手电/音量/亮度/震动/语音/通知/启动应用/查询状态)时,"
            + "直接计划 device.cmd 真做, 禁止写成 HTML 模拟。"
            + "能力边界 (CONTRACT_CAPABILITY 压缩版, 对照执行):"
            + "✅能做全做=单文件HTML工具/游戏→APK+localStorage/IndexedDB+文件读写+宿主设备控制"
            + "(手电/音量/亮度/震动/TTS/通知/剪贴板/电量/截屏/拍照/定位/通讯录/短信/应用启动)"
            + "+APK内浏览器能力(HTTPS联网fetch/相机getUserMedia(前置默认; 扫一扫/拍照用后置须写"
            + "facingMode:'environment')/录音recordAudio走MovShell"
            + "/体感传感器DeviceMotion/音频播放/WebGL/手柄Gamepad)"
            + "+APK内JS桥MovShell(全部同步返回值, 严禁当回调用): "
            + "notify(title,text)→\"ok\"/\"no-permission\"; vibrate(ms)→布尔(false=无马达), hasVibrator()→布尔; "
            + "recordAudio(秒)→\"recording:N\"/\"err\", 到点后 recordResult()→\"ok:字节:路径\"/\"err\", "
            + "recordBase64()→base64或null(录音中返回null)。语音消息正确写法(轮询防竞态): "
            + "recordAudio(3) → 每500ms查一次recordBase64()直到非null(最多查10次) → "
            + "存'data:audio/mp4;base64,'+b64(容器m4a不是ogg), 禁止用固定setTimeout猜完成时间;"
            + "+内嵌Ubuntu24.04完整Linux shell(shell.exec): apt装包/python3/git/联网抓取/真实构建,"
            + "工作目录/root; 房间文件先file.push送进/exchange, 产物放/exchange再file.pull取回;"
            + "已授权时可读写/sdcard(含Obsidian vault路径);"
            + "shell纪律=大输出重定向到文件再grep/tail分段看, 长构建timeoutSec给足(最大600秒),"
            + "产物必须file.pull取回房间才算交付;"
            + "pip纪律=Ubuntu24系统python禁止pip直装(PEP668), 装包用 python3 -m venv 建虚拟环境"
            + "或 pip install --break-system-packages;"
            + "重任务委派=shell.exec 里可调内嵌 Hermes agent (深度调研/大文件生成/多步骤自动化):"
            + "~/.hermes-venv/bin/hermes -z '<任务描述>' (headless 单发, 自动批准工具, 模型配置已注入;"
            + "timeoutSec 给足 300-600; 产物让它写到 /exchange 再 file.pull 取回);"
            + "+全栈交付=可写真实后端 (FastAPI/Express), 内嵌 Linux 本地起服务 curl 自测,"
            + "再 ssh/scp 部署到用户服务器 (别名 mov-deploy: 用 movssh/movscp 包装命令;"
            + "需用户先在设置页配好部署服务器, 未配置→ask_user 引导), APK/前端经 HTTPS 对接;"
            + "部署纪律=服务用 systemd unit 或 nohup+日志落盘守护, 部署后必须 curl 健康检查再交付,"
            + "长部署命令 timeoutSec 给足; 提示用户防火墙开端口; 有域名→nginx 反代+certbot 签证书;"
            + "无域名→诚实说明 WebView 拦明文 HTTP, 需 HTTPS/自签信任方案, 不许假装能直连;"
            + "⚠️带条件=APK内明文HTTP被系统拦(targetSdk36, 须服务器上HTTPS或走代理), "
            + "多用户/账号体系界面、财务系统(无后端, 必须在计划里明示「演示版」及边界);"
            + "❌做不了=打包APK内定位/支付接口/应用推送"
            + " — 禁止闷头糊界面, 先说明做不到并给替代(如交付 server.js+部署说明 或 收款码图片方案);"
            + "完成度标准: 承诺的功能全部真实现, 禁止占位符/桩代码/TODO/色块当图片/按钮无响应。"
            + "理解闸: 当目标存在多种合理的场景解读(给谁用/在哪用不明确, 如「做个点单应用」"
            + "可能是顾客扫码用也可能是老板记账用)时, 禁止猜, 改输出 "
            + "{\"clarify\":{\"question\":\"一句话确认问题\",\"options\":[\"最可能的选项A\",\"选项B\",\"选项C\"]}}, "
            + "options 给 2-3 个用户最可能选的具体选项(不是「是/否」), 拿到回答后再出计划;"
            + "只有唯一合理解读时才直接出计划, 并在 understanding.guess 里写明你猜的部分。";

    private static final String STEP_RULES =
            "你是 MOV agent 的大脑, 正在驱动一个 agentic 循环。规则:\n"
            + "1. 每轮只输出一个 JSON 动作, 不要输出其他文字。\n"
            + "2. 流程控制动作: {\"action\":\"ask_user\",\"question\":\"问用户的问题\",\"options\":[\"选项A\",\"选项B\"](可选, 有就给出 2-3 个具体选项)} | "
            + "{\"action\":\"revise_plan\",\"reason\":\"原因\",\"plan\":[...]}(全任务最多 2 次, 用完只能执行或 finish) | "
            + "{\"action\":\"finish\",\"summary\":\"交付说明\"}\n"
            + "finish 的 summary 格式纪律 (诚实交付): 先一段交付说明, 然后必须有【功能自检清单】—"
            + "逐项列出计划承诺的每个功能, 每行一条: 「- ✅ 功能名: 真实现」或「- ⚠️ 功能名: 演示版(原因)」,"
            + "禁止漏项; 做不到真实现的功能必须标 ⚠️ 并写明原因, 不许含糊带过;"
            + "做不出真功能的 UI 元素 (按钮/控件) 必须在产物里带可见「演示」角标, 禁止死按钮。\n"
            + "3. file.write 只能写已批准计划中的文件, content 必须是完整最终内容。\n"
            + "4. 工具结果在工作日志里, 根据结果决定下一步; 需要回看文件内容用 file.read;"
            + "仅当结果尾部明确提示『继续读』才用 offset 续读, 显示『文件读完』就禁止再读同一文件。\n"
            + "5. 目标完成就输出 finish。\n"
            + "6. ask_user 仅限真正卡住(缺信息且无法自行判断)时使用; 用户发来的修改/反馈意见"
            + "本身就是指令, 直接照做, 禁止反问「是否需要我修改」这类确认问题。\n"
            + "7. .apk 是 .html 的打包产物 — 改游戏永远改源 .html 再重新打包, 不要问用户玩的是哪个文件。\n"
            + "8. 工具纪律: 已读过的文件内容就在上方工作日志里, 禁止重复 file.read 同一文件;"
            + "每个疑问最多 2-3 次工具调用, 没有明确下一步就立即 finish, 不要边缘试探。\n"
            + "9. 部署纪律 (全栈任务): 服务先在内嵌 Linux 本地起 curl 自测通过才允许部署;"
            + "部署用 movssh/movscp mov-deploy; 服务守护用 systemd 或 nohup+日志落盘;"
            + "长部署命令 timeoutSec 给足; 部署后必须 curl 健康检查通过才 finish 交付。";
}
