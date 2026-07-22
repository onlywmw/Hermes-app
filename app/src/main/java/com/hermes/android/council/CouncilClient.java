package com.hermes.android.council;

import com.hermes.android.ai.AiClient;
import com.hermes.android.model.ModelConfig;
import com.hermes.android.model.ModelRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Council 多模型真实讨论 (L2)。
 * 接收 modelIds → 并行调用各模型 → 默认模型汇总 → 结构化输出。
 * 替代旧版硬编码 claude/gpt-5/gemini 剧本。
 */
public class CouncilClient {

    private static final int MAX_PARALLEL = 3;
    private static final int TIMEOUT_SECONDS = 30;

    private final ModelRegistry registry;

    public CouncilClient(ModelRegistry registry) {
        this.registry = registry;
    }

    /** 单模型回复 */
    public static class ModelReply {
        public final String modelId;
        public final String name;
        public final String role;
        public final String color;
        public final String content;
        public final boolean success;

        public ModelReply(String modelId, String name, String role,
                          String color, String content, boolean success) {
            this.modelId = modelId;
            this.name = name;
            this.role = role;
            this.color = color;
            this.content = content;
            this.success = success;
        }
    }

    /**
     * 执行 Council 讨论。
     *
     * @param topic    议题
     * @param modelIds 参与模型 ID 列表
     * @param context  额外上下文 (房间描述/文件列表/前几轮摘要), 可为 null
     * @return JSON: {ok, messages:[{who,name,role,color,content}], summary, nextSteps}
     */
    public String discuss(String topic, List<String> modelIds, String context) {
        if (modelIds == null || modelIds.isEmpty()) {
            return "{\"ok\":false,\"error\":\"没有选择参与讨论的模型\"}";
        }

        // 收集有效模型
        List<ModelConfig> models = new ArrayList<>();
        for (String id : modelIds) {
            ModelConfig mc = registry.get(id);
            if (mc != null && mc.enabled && mc.isConfigured()) {
                models.add(mc);
            }
        }
        if (models.isEmpty()) {
            return "{\"ok\":false,\"error\":\"所选模型均未配置或已禁用\"}";
        }

        try {
            // 构造公共上下文
            StringBuilder ctx = new StringBuilder();
            if (context != null && !context.trim().isEmpty()) {
                ctx.append("项目背景:\n").append(context.trim()).append("\n\n");
            }

            // 并行调用
            ExecutorService exec = Executors.newFixedThreadPool(
                    Math.min(models.size(), MAX_PARALLEL));
            List<Future<ModelReply>> futures = new ArrayList<>();

            for (ModelConfig mc : models) {
                final String rolePrompt = buildRolePrompt(mc, topic, ctx.toString());
                futures.add(exec.submit(new Callable<ModelReply>() {
                    @Override
                    public ModelReply call() {
                        AiClient client = new AiClient(mc, rolePrompt);
                        AiClient.AiResponse resp = client.chat(
                                "议题: " + topic + "\n请从你的角色角度给出观点。简洁, 3 句话以内。");
                        return new ModelReply(
                                mc.id,
                                mc.name.isEmpty() ? mc.getProviderDisplayName() : mc.name,
                                mc.role,
                                mc.color,
                                resp.success ? resp.content : "(调用失败: " + resp.content + ")",
                                resp.success);
                    }
                }));
            }

            // 收齐结果
            JSONArray messages = new JSONArray();
            StringBuilder councilContext = new StringBuilder(
                    "以下是各专家对「" + topic + "」的讨论:\n\n");

            for (Future<ModelReply> f : futures) {
                ModelReply reply = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                JSONObject msg = new JSONObject();
                msg.put("who", reply.modelId);
                msg.put("name", reply.name);
                msg.put("role", reply.role);
                msg.put("color", reply.color);
                msg.put("content", reply.content);
                messages.put(msg);

                councilContext.append(reply.name)
                        .append("(").append(reply.role).append("): ")
                        .append(reply.content).append("\n\n");
            }
            exec.shutdown();

            // 汇总 (用默认模型)
            ModelConfig defaultModel = registry.getDefault();
            String summary = "(汇总失败: 无默认模型)";
            String nextSteps = "[]";

            if (defaultModel != null && defaultModel.isConfigured()) {
                String summaryPrompt = "你是会议主持人。请根据以下讨论:\n"
                        + "1. 用 2-3 句话总结共识和分歧\n"
                        + "2. 给出推荐方案\n"
                        + "3. 列出下一步行动 (JSON 数组, 每项 {action, target, detail})\n"
                        + "用中文回答。";

                AiClient summarizer = new AiClient(defaultModel, summaryPrompt);
                AiClient.AiResponse summaryResp = summarizer.chat(
                        councilContext.toString()
                        + "\n请总结并给出下一步行动计划。"
                        + "\n下一步行动请用 JSON 数组格式: [{\"action\":\"file.write\",\"target\":\"文件名\",\"detail\":\"内容描述\"}]");

                if (summaryResp.success) {
                    summary = summaryResp.content;
                    // 尝试提取 JSON 步骤
                    nextSteps = extractNextSteps(summaryResp.content);
                }
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("messages", messages);
            result.put("summary", summary);
            result.put("nextSteps", nextSteps);
            return result.toString();

        } catch (java.util.concurrent.TimeoutException e) {
            return "{\"ok\":false,\"error\":\"讨论超时 (>" + TIMEOUT_SECONDS + "s), 请检查网络\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"Council 调用异常: " + e.getMessage() + "\"}";
        }
    }

    /** 兼容旧接口: 用所有已启用模型 */
    public String discuss(String topic) {
        List<String> ids = new ArrayList<>();
        for (ModelConfig mc : registry.list()) {
            if (mc.enabled && mc.isConfigured()) ids.add(mc.id);
        }
        return discuss(topic, ids, null);
    }

    /** 构造角色 system prompt */
    private String buildRolePrompt(ModelConfig mc, String topic, String context) {
        String roleName = mc.role != null && !mc.role.isEmpty() ? mc.role : "通用";
        String name = mc.name.isEmpty() ? mc.getProviderDisplayName() : mc.name;
        return "你是 " + name + ", 扮演" + roleName + "角色。"
                + "你关注" + roleFocus(roleName) + "。"
                + "回答简洁, 3 句话以内。用中文。"
                + (mc.systemPrompt != null && !mc.systemPrompt.trim().isEmpty()
                   ? "\n补充指令: " + mc.systemPrompt : "");
    }

    private String roleFocus(String role) {
        switch (role) {
            case "产品": return "用户体验、MVP 范围、产品优先级";
            case "技术": return "技术选型、架构可行性、交付周期";
            case "数据": return "留存、指标、用户行为分析";
            default:     return "全局视角、风险与机会";
        }
    }

    /** Callback — 每收到一个模型回复或汇总时调用 */
    public interface ReplyCallback {
        void onReply(JSONObject reply);
    }

    /**
     * 伪流式讨论: 每个模型回复到达即回调 JS (DESIGN_HYBRID v2.0).
     * 先到先显, 无需 SSE/云函数。
     */
    public void discussAsync(String topic, List<String> modelIds, String context,
                              ReplyCallback callback) {
        if (modelIds == null || modelIds.isEmpty()) {
            try {
                callback.onReply(new JSONObject()
                        .put("type", "error")
                        .put("content", "没有选择参与讨论的模型"));
            } catch (Exception ignored) {}
            return;
        }

        List<ModelConfig> models = new ArrayList<>();
        for (String id : modelIds) {
            ModelConfig mc = registry.get(id);
            if (mc != null && mc.enabled && mc.isConfigured()) models.add(mc);
        }
        if (models.isEmpty()) {
            try {
                callback.onReply(new JSONObject()
                        .put("type", "error")
                        .put("content", "所选模型均未配置或已禁用"));
            } catch (Exception ignored) {}
            return;
        }

        StringBuilder ctx = new StringBuilder();
        if (context != null && !context.trim().isEmpty()) {
            ctx.append("项目背景:\n").append(context.trim()).append("\n\n");
        }

        ExecutorService exec = Executors.newFixedThreadPool(
                Math.min(models.size(), MAX_PARALLEL));
        CompletionService<ModelReply> cs = new ExecutorCompletionService<>(exec);

        for (ModelConfig mc : models) {
            final String rolePrompt = buildRolePrompt(mc, topic, ctx.toString());
            cs.submit(new Callable<ModelReply>() {
                @Override
                public ModelReply call() {
                    AiClient client = new AiClient(mc, rolePrompt);
                    AiClient.AiResponse resp = client.chat(
                            "议题: " + topic + "\n请从你的角色角度给出观点。简洁, 3 句话以内。");
                    return new ModelReply(
                            mc.id,
                            mc.name.isEmpty() ? mc.getProviderDisplayName() : mc.name,
                            mc.role, mc.color,
                            resp.success ? resp.content : "(调用失败: " + resp.content + ")",
                            resp.success);
                }
            });
        }

        // 谁先返回先回调 (CompletionService)
        StringBuilder councilContext = new StringBuilder(
                "以下是各专家对「" + topic + "」的讨论:\n\n");

        for (int i = 0; i < models.size(); i++) {
            try {
                Future<ModelReply> f = cs.take(); // 阻塞等下一个完成的
                ModelReply reply = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                JSONObject msg = new JSONObject();
                msg.put("type", "model");
                msg.put("who", reply.modelId);
                msg.put("name", reply.name);
                msg.put("role", reply.role);
                msg.put("color", reply.color);
                msg.put("content", reply.content);
                msg.put("success", reply.success);
                callback.onReply(msg); // ← 立即推给 JS

                councilContext.append(reply.name)
                        .append("(").append(reply.role).append("): ")
                        .append(reply.content).append("\n\n");
            } catch (Exception e) {
                try {
                    callback.onReply(new JSONObject()
                            .put("type", "model")
                            .put("who", "?")
                            .put("name", "调用超时")
                            .put("content", "模型超时未响应"));
                } catch (Exception ignored) {}
            }
        }
        exec.shutdown();

        // 全部收齐 → 汇总
        ModelConfig defaultModel = registry.getDefault();
        if (defaultModel != null && defaultModel.isConfigured()) {
            String summaryPrompt = "你是会议主持人。请根据以下讨论:\n"
                    + "1. 用 2-3 句话总结共识和分歧\n"
                    + "2. 给出推荐方案\n"
                    + "3. 列出下一步行动 (JSON 数组, 每项 {action, target, detail})\n"
                    + "用中文回答。";

            AiClient summarizer = new AiClient(defaultModel, summaryPrompt);
            AiClient.AiResponse summaryResp = summarizer.chat(
                    councilContext.toString()
                    + "\n请总结并给出下一步行动计划。"
                    + "\n下一步行动请用 JSON 数组格式: [{\"action\":\"file.write\",\"target\":\"文件名\",\"detail\":\"内容描述\"}]");

            if (summaryResp.success) {
                try {
                    JSONObject sum = new JSONObject();
                    sum.put("type", "summary");
                    sum.put("summary", summaryResp.content);
                    sum.put("nextSteps", extractNextSteps(summaryResp.content));
                    callback.onReply(sum); // ← 推汇总
                } catch (Exception ignored) {}
            }
        }
    }

    /** 从汇总文本中提取 JSON 步骤数组 */
    private String extractNextSteps(String text) {
        try {
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String json = text.substring(start, end + 1);
                new JSONArray(json); // 验证合法性
                return json;
            }
        } catch (Exception ignored) {}
        return "[]";
    }
}
