package com.hermes.android.council;

import com.hermes.android.ai.AiClient;
import com.hermes.android.ai.AiProviderConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * P1-5: Council 多角色讨论。
 * P1-3: 并行化 — 3 个角色并发调用，延迟从 4x 降到 ~1.5x。
 */
public class CouncilClient {

    private static final String[][] ROLES = {
        {"claude", "产品", "你是 Claude, 扮演产品经理角色。你关注用户体验、MVP 范围、产品优先级。回答简洁, 3 句话以内。用中文。"},
        {"gpt-5", "技术", "你是 GPT-5, 扮演技术架构师角色。你关注技术选型、可行性、交付周期。回答简洁, 3 句话以内。用中文。"},
        {"gemini", "数据", "你是 Gemini, 扮演数据分析/增长角色。你关注留存、指标、用户行为。回答简洁, 3 句话以内。用中文。"}
    };

    private final AiProviderConfig config;

    public CouncilClient(AiProviderConfig config) {
        this.config = config;
    }

    /**
     * 执行 Council 讨论, 返回 JSON:
     * {"ok":true, "messages":[{"who":"claude","role":"产品","content":"..."},...], "summary":"..."}
     */
    public String discuss(String topic) {
        if (!config.isAiEnabled() || !config.isConfigured()) {
            return "{\"ok\":false,\"error\":\"AI 未配置, 无法召开 Council。点右上角 ≡ 设置。\"}";
        }

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            // P1-3: 3 个角色并行调用
            List<Future<JSONObject>> futures = new ArrayList<>();
            for (String[] role : ROLES) {
                final String who = role[0];
                final String roleName = role[1];
                final String systemPrompt = role[2];
                futures.add(pool.submit(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() {
                        try {
                            AiClient client = new AiClient(config, systemPrompt);
                            AiClient.AiResponse resp = client.chat(
                                    "议题: " + topic + "\n请从你的角色角度给出观点。",
                                    new ArrayList<AiClient.Message>());
                            JSONObject msg = new JSONObject();
                            msg.put("who", who);
                            msg.put("role", roleName);
                            msg.put("content", resp.success ? resp.content
                                    : "(调用失败: " + resp.content + ")");
                            return msg;
                        } catch (Exception e) {
                            try {
                                return new JSONObject()
                                        .put("who", who)
                                        .put("role", roleName)
                                        .put("content", "(异常: " + e.getMessage() + ")");
                            } catch (Exception ex) {
                                return null;
                            }
                        }
                    }
                }));
            }

            // 等待所有角色返回 (最慢的决定总延迟)
            JSONArray messages = new JSONArray();
            for (Future<JSONObject> f : futures) {
                JSONObject msg = f.get(60, TimeUnit.SECONDS);
                if (msg != null) messages.put(msg);
            }

            // 汇总: 串行第 4 次调用
            AiClient summarizer = new AiClient(config);
            StringBuilder councilContext = new StringBuilder(
                    "以下是三位专家对「" + topic + "」的讨论:\n\n");
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.getJSONObject(i);
                councilContext.append(m.getString("who"))
                        .append("(").append(m.getString("role")).append("): ")
                        .append(m.getString("content")).append("\n\n");
            }
            councilContext.append("请用 3 句话总结共识和分歧, 给出推荐方案。");

            AiClient.AiResponse summaryResp = summarizer.chat(
                    councilContext.toString(), new ArrayList<AiClient.Message>());

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("messages", messages);
            result.put("summary", summaryResp.success ? summaryResp.content : "(汇总失败)");
            return result.toString();

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "未知错误";
            return "{\"ok\":false,\"error\":\"Council 调用异常: " + msg + "\"}";
        } finally {
            pool.shutdownNow();
        }
    }
}
