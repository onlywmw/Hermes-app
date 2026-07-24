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
    public JSONObject deliveryVote(String goal, String logDigest, JSONArray files, String productsDigest) {
        JSONObject out = new JSONObject();
        JSONArray comments = new JSONArray();
        int[] tokens = {0, 0};
        int pass = 0, fail = 0;
        try {
            /* 无评审模型: 明确 notReviewed, 不再 pass=1 假装通过 */
            if (reviewers == null || reviewers.isEmpty()) {
                return out.put("pass", 0).put("fail", 0).put("notReviewed", true)
                        .put("comments", comments).put("pt", 0).put("ct", 0);
            }
            String prompt = "你是交付评审员, 对 agent 的交付投票。你能看到目标、工作日志摘要,"
                    + "以及产物内容节选 (含 HTML 交互元素清单)。\n"
                    + "评审方式: 逐一对照计划承诺的功能, 在下面的产物内容里找实现证据。\n"
                    + "发现以下任一硬伤, 投 false 并指出具体位置 (文件名+元素):\n"
                    + "- 死按钮: button/控件没有事件绑定, 或绑定的函数是空函数体/只打日志\n"
                    + "- 占位符/桩代码/TODO/「后续实现」\n"
                    + "- 计划承诺的文件缺失, 或内容与承诺功能明显不符\n"
                    + "怀疑但内容里找不到直接证据的, 投 true (防误报返工白烧 token)。\n"
                    + "不要报: 缺美术资源/文件该拆分/代码风格 — 这些不是交付缺陷。\n"
                    + logDigest
                    + "\n产物内容节选:\n" + (productsDigest != null ? productsDigest : "(无)")
                    + "\n只输出 JSON: {\"pass\":true或false,\"reason\":\"一句理由, false 时必须引用产物中的具体位置\"}。";
            int[] votes = {0, 0};
            parallelCollect(prompt, (name, role, resp) -> {
                if (resp == null) return;
                /* 评审调用失败: 只记录, 不计票 (修复 fail-open「调用失败视为通过」) */
                if (!resp.success) {
                    try {
                        comments.put(new JSONObject().put("name", name)
                                .put("pass", JSONObject.NULL)
                                .put("reason", "(评审调用失败, 不计票)"));
                    } catch (Exception ignored) {}
                    return;
                }
                boolean p = true;
                String reason = "(无反馈)";
                String json = ActionParser.extractJson(resp.content);
                if (json != null) {
                    try {
                        JSONObject v = new JSONObject(json);
                        p = v.optBoolean("pass", true);
                        reason = v.optString("reason", reason);
                    } catch (Exception ignored) {}
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
