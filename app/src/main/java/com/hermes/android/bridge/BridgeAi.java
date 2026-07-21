package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.StatsCollector;
import com.hermes.android.ai.AiClient;
import com.hermes.android.ai.AiProviderConfig;
import com.hermes.android.council.CouncilClient;
import com.hermes.android.model.ModelRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * AI Bridge — 异步对话 + Council + 配置查询
 */
public class BridgeAi extends BaseBridge {

    private final AiProviderConfig aiConfig;
    private final ModelRegistry modelRegistry;
    private final ExecutorService aiExecutor;
    private final List<AiClient.Message> chatHistory;
    private final StatsCollector statsCollector;
    private static final int MAX_HISTORY = 10;

    public BridgeAi(HermesActivity activity) {
        super(activity);
        this.aiConfig = activity.getAiConfig();
        this.modelRegistry = activity.getModelRegistry();
        this.aiExecutor = activity.getAiExecutor();
        this.chatHistory = activity.getChatHistory();
        this.statsCollector = activity.getStatsCollector();
    }

    public void aiChatAsync(String text, String callbackId) {
        aiExecutor.execute(() -> {
            String resultJson;
            try {
                if (!aiConfig.isAiEnabled()) {
                    resultJson = "{\"ok\":false,\"content\":\"AI 已关闭, 点右上角 ≡ 可启用。\"}";
                } else if (!aiConfig.isConfigured()) {
                    resultJson = "{\"ok\":false,\"content\":\"AI 尚未配置 API Key, 点右上角 ≡ 设置后即可畅聊。\"}";
                } else {
                    AiClient client = new AiClient(aiConfig);
                    List<AiClient.Message> history;
                    synchronized (chatHistory) {
                        history = new ArrayList<>(chatHistory);
                    }
                    long aiT0 = System.currentTimeMillis();
                    AiClient.AiResponse resp = client.chat(text, history);
                    long aiMs = System.currentTimeMillis() - aiT0;
                    if (statsCollector != null) {
                        statsCollector.recordAiCall(aiConfig.getProvider(),
                                aiConfig.getModel(), aiMs, resp.success);
                    }
                    if (resp.success) {
                        synchronized (chatHistory) {
                            chatHistory.add(new AiClient.Message("user", text));
                            chatHistory.add(new AiClient.Message("assistant", resp.content));
                            while (chatHistory.size() > MAX_HISTORY * 2) chatHistory.remove(0);
                        }
                        activity.saveChatHistoryPublic();
                        resultJson = new JSONObject()
                                .put("ok", true)
                                .put("content", resp.content).toString();
                    } else {
                        resultJson = new JSONObject()
                                .put("ok", false)
                                .put("content", "AI 调用失败: " + resp.content).toString();
                    }
                }
            } catch (Exception e) {
                try {
                    resultJson = new JSONObject()
                            .put("ok", false)
                            .put("content", "AI 调用异常: " + e.getMessage()).toString();
                } catch (Exception ex) {
                    resultJson = "{\"ok\":false,\"content\":\"AI 调用异常\"}";
                }
            }
            evalJs("window._hermesCb('" + callbackId + "'," + resultJson + ")");
        });
    }

    public void councilAsync(String topic, String callbackId) {
        councilAsync(topic, "[]", null, callbackId);
    }

    public void councilAsync(String topic, String modelIdsJson, String context, String callbackId) {
        aiExecutor.execute(() -> {
            try {
                CouncilClient council = new CouncilClient(modelRegistry);
                List<String> ids = new ArrayList<>();
                try {
                    JSONArray arr = new JSONArray(modelIdsJson);
                    for (int i = 0; i < arr.length(); i++) ids.add(arr.getString(i));
                } catch (Exception ignored) {}
                String resultJson = council.discuss(topic, ids.isEmpty() ? null : ids, context);
                evalJs("window._hermesCb('" + callbackId + "'," + resultJson + ")");
            } catch (Exception e) {
                evalJs("window._hermesCb('" + callbackId +
                        "',{\"ok\":false,\"error\":\"" + e.getMessage() + "\"})");
            }
        });
    }

    public String aiChat(String text) {
        if (!aiConfig.isAiEnabled()) return "AI 已关闭, 点右上角 ≡ 可启用。";
        if (!aiConfig.isConfigured()) return "AI 尚未配置 API Key, 点右上角 ≡ 设置后即可畅聊。";
        try {
            AiClient client = new AiClient(aiConfig);
            AiClient.AiResponse resp = client.chat(text, new ArrayList<>());
            if (resp.success) return resp.content;
            return "AI 调用失败: " + resp.content;
        } catch (Exception e) {
            return "AI 调用异常: " + e.getMessage();
        }
    }

    public String getAiInfo() {
        try {
            return new JSONObject()
                    .put("enabled", aiConfig.isAiEnabled())
                    .put("configured", aiConfig.isConfigured())
                    .put("displayName", aiConfig.getProviderDisplayName().toUpperCase())
                    .put("model", aiConfig.getModel())
                    .put("summary", aiConfig.getStatusSummary())
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public String getLanguage() { return aiConfig.getLanguage(); }
    public void setLanguage(String lang) { aiConfig.setLanguage(lang); }
}
