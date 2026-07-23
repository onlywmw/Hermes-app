package com.hermes.android.agent;

import com.hermes.android.ai.AiClient;
import com.hermes.android.model.ModelConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * AgentReview — v2 评审团 (DESIGN_AGENT_LOOP: 1 驱动 + N 评审)。
 *
 * 复用 CouncilClient 的并行 CompletionService 模式, 但职责不同:
 * - planReview: 每个评审模型对计划给 3 句话意见 (风险/遗漏/优先级)
 * - deliveryVote: 每个评审模型对交付投票 {pass, reason}
 * 返回均含 pt/ct (token 消耗, 供循环单独计量)。
 * 单个评审超时/失败静默跳过, 不阻塞主循环。
 *
 * 解耦: 不直接 new AiClient — 大脑由 BrainFactory 注入 (与 AgentLoop 同一接口),
 * 生产环境在 BridgeAi 组装, 测试可注入假脑。
 */
public class AgentReview implements AgentLoop.Reviewer {

    /** 按模型造大脑 (与 AgentLoop.Brain 同一签名) */
    public interface BrainFactory {
        AgentLoop.Brain of(ModelConfig mc);
    }

    private static final int MAX_PARALLEL = 3;
    private static final int TIMEOUT_SECONDS = 30;

    private final List<ModelConfig> reviewers;
    private final BrainFactory brainOf;

    public AgentReview(List<ModelConfig> reviewers, BrainFactory brainOf) {
        this.reviewers = reviewers;
        this.brainOf = brainOf;
    }

    @Override
    public JSONObject planReview(String goal, JSONArray plan) {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        int[] tokens = {0, 0};
        try {
            out.put("items", items);
            if (reviewers == null || reviewers.isEmpty()) return out;

            String prompt = "你是评审团专家。用户目标: " + goal + "\n计划:\n" + plan.toString()
                    + "\n用 3 句话以内给出评审: 最大风险 / 遗漏点 / 优先级建议。用中文, 只说意见。";
            parallelCollect(prompt, (name, role, resp) -> {
                if (resp == null) return;
                try {
                    items.put(new JSONObject()
                            .put("name", name).put("role", role)
                            .put("comment", resp.success ? resp.content : "(评审调用失败)"));
                } catch (Exception ignored) {}
            }, tokens);
        } catch (Exception ignored) {}
        try { out.put("pt", tokens[0]).put("ct", tokens[1]); } catch (Exception ignored) {}
        return out;
    }

    @Override
    public JSONObject deliveryVote(String goal, String logDigest, JSONArray files) {
        JSONObject out = new JSONObject();
        JSONArray comments = new JSONArray();
        int[] tokens = {0, 0};
        int pass = 0, fail = 0;
        try {
            if (reviewers == null || reviewers.isEmpty()) {
                return out.put("pass", 1).put("fail", 0).put("comments", comments)
                        .put("pt", 0).put("ct", 0);
            }
            String prompt = "你是交付评审员, 对 agent 的交付投票。你只能看到目标和工作日志摘要,"
                    + "执行者接触过你看不到的完整上下文 (文件全文/工具结果)。\n"
                    + "核心原则: 证伪而非证实 — 只有当日志/文件清单里有直接证据表明交付有缺陷时才投 false;"
                    + "怀疑但无法证实的, 一律投 true (借鉴 OpenCodeReview 的 veto rule, 防误报返工白烧 token)。\n"
                    + "验收清单 (逐项对照): 1.承诺的产出文件都在清单里 2.单文件应用自洽(不引用不存在的本地资源) "
                    + "3.交互元素真绑定了事件(按钮/方向键不是摆设) 4.要求持久化的数据用了 localStorage。\n"
                    + "不要报: 缺后端/缺美术资源/文件该拆分/代码风格 — 这些不是交付缺陷。\n"
                    + logDigest
                    + "\n只输出 JSON: {\"pass\":true或false,\"reason\":\"一句理由, false 时必须引用日志中的证据\"}。";
            // 并行收集 (需要可变计数, 用数组)
            int[] votes = {0, 0};
            parallelCollect(prompt, (name, role, resp) -> {
                if (resp == null) return;
                boolean p = true;
                String reason = "(无反馈)";
                if (!resp.success) {
                    reason = "(评审调用失败, 视为通过)";
                } else {
                    String json = ActionParser.extractJson(resp.content);
                    if (json != null) {
                        try {
                            JSONObject v = new JSONObject(json);
                            p = v.optBoolean("pass", true);
                            reason = v.optString("reason", reason);
                        } catch (Exception ignored) {}
                    }
                }
                try {
                    comments.put(new JSONObject()
                            .put("name", name).put("pass", p).put("reason", reason));
                } catch (Exception ignored) {}
                if (p) votes[0]++; else votes[1]++;
            }, tokens);
            pass = votes[0]; fail = votes[1];
        } catch (Exception ignored) {}
        try {
            out.put("pass", pass).put("fail", fail).put("comments", comments)
                    .put("pt", tokens[0]).put("ct", tokens[1]);
        } catch (Exception ignored) {}
        return out;
    }

    // ==================== 并行执行 (CouncilClient 模式) ====================

    private interface Collector {
        void accept(String name, String role, AiClient.AiResponse resp);
    }

    private void parallelCollect(String prompt, Collector collector, int[] tokens) {
        // 结果携带模型身份 (CompletionService 按完成序返回, 不能用提交序对号入座)
        class Named {
            final String name, role;
            final AiClient.AiResponse resp;
            Named(String name, String role, AiClient.AiResponse resp) {
                this.name = name; this.role = role; this.resp = resp;
            }
        }
        ExecutorService exec = Executors.newFixedThreadPool(
                Math.min(reviewers.size(), MAX_PARALLEL));
        CompletionService<Named> cs = new ExecutorCompletionService<>(exec);
        final String sp = "你是严格、简洁的评审专家。只说要点, 用中文。";
        int submitted = 0;
        for (ModelConfig mc : reviewers) {
            if (mc == null || !mc.isConfigured()) continue;
            submitted++;
            final AgentLoop.Brain brain = brainOf.of(mc);
            final String name = mc.name.isEmpty() ? mc.getProviderDisplayName() : mc.name;
            final String role = mc.role;
            cs.submit(new Callable<Named>() {
                @Override
                public Named call() {
                    return new Named(name, role, brain.chat(sp, prompt));
                }
            });
        }
        for (int i = 0; i < submitted; i++) {
            try {
                Future<Named> f = cs.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (f == null) break; // 超时: 剩余评审跳过
                Named n = f.get();
                if (n.resp != null) {
                    tokens[0] += Math.max(0, n.resp.promptTokens);
                    tokens[1] += Math.max(0, n.resp.completionTokens);
                }
                collector.accept(n.name, n.role, n.resp);
            } catch (Exception e) {
                break;
            }
        }
        exec.shutdownNow();
    }
}
