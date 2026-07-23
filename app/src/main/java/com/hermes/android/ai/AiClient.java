package com.hermes.android.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal OpenAI-compatible chat client.
 * Works with OpenAI, DeepSeek, Qwen (compatible-mode), Ollama (/v1/chat/completions).
 */
public class AiClient {

    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class AiResponse {
        public final boolean success;
        public final String content;
        /** DESIGN_AGENT_LOOP 计量: prompt/completion tokens; 无 usage 时为字符数÷4 估算 */
        public final int promptTokens;
        public final int completionTokens;
        public final boolean tokensEstimated;

        public AiResponse(boolean success, String content) {
            this(success, content, -1, -1, false);
        }

        public AiResponse(boolean success, String content,
                          int promptTokens, int completionTokens, boolean tokensEstimated) {
            this.success = success;
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.tokensEstimated = tokensEstimated;
        }
    }

    private final AiProviderConfig config;
    private final com.hermes.android.model.ModelConfig modelConfig;

    /** 临时覆盖 system prompt（仅本次实例，不持久化）。
     *  用于 Council 等多角色场景，避免并发写 SharedPreferences。 */
    private String overrideSystemPrompt = null;

    public AiClient(AiProviderConfig config) {
        this.config = config;
        this.modelConfig = null;
    }

    /** 构造时指定临时 system prompt，不修改 SharedPreferences */
    public AiClient(AiProviderConfig config, String systemPrompt) {
        this.config = config;
        this.modelConfig = null;
        this.overrideSystemPrompt = systemPrompt;
    }

    /** 从 ModelConfig 构造 (多模型场景, 不依赖全局 AiProviderConfig) */
    public AiClient(com.hermes.android.model.ModelConfig mc) {
        this.config = null;
        this.modelConfig = mc;
    }

    /** 从 ModelConfig 构造 + 临时 system prompt */
    public AiClient(com.hermes.android.model.ModelConfig mc, String systemPrompt) {
        this.config = null;
        this.modelConfig = mc;
        this.overrideSystemPrompt = systemPrompt;
    }

    private String getBaseUrl() {
        return modelConfig != null ? modelConfig.getEffectiveBaseUrl() : config.getBaseUrl();
    }

    private String getApiKey() {
        return modelConfig != null ? modelConfig.apiKey : config.getApiKey();
    }

    private String getModelName() {
        return modelConfig != null ? modelConfig.getEffectiveModel() : config.getModel();
    }

    private String getSystemPrompt() {
        if (overrideSystemPrompt != null) return overrideSystemPrompt;
        if (modelConfig != null && modelConfig.systemPrompt != null
                && !modelConfig.systemPrompt.trim().isEmpty()) return modelConfig.systemPrompt;
        if (config != null) return config.getSystemPrompt();
        return "你是 MOV，一个运行在 Android 平板上的多模型协作工作台。回答用中文。";
    }

    public AiResponse chat(String userText) {
        return chat(userText, new ArrayList<>());
    }

    /**
     * Send user text with optional recent history (user/assistant messages).
     */
    public AiResponse chat(String userText, List<Message> history) {
        HttpURLConnection conn = null;
        try {
            String baseUrl = normalizeBaseUrl(getBaseUrl());
            URL url = new URL(baseUrl + "/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            String apiKey = getApiKey();
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }

            JSONObject body = new JSONObject();
            body.put("model", getModelName());
            body.put("stream", false);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", getSystemPrompt()));

            if (history != null) {
                for (Message m : history) {
                    if (m == null || m.content == null) continue;
                    if (!"user".equals(m.role) && !"assistant".equals(m.role)) continue;
                    messages.put(new JSONObject()
                            .put("role", m.role)
                            .put("content", m.content));
                }
            }

            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userText));

            body.put("messages", messages);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
                os.flush();
            }

            int code = conn.getResponseCode();
            String responseBody;
            if (code >= 200 && code < 300) {
                responseBody = readStream(conn);
            } else {
                String err = readErrorStream(conn);
                return new AiResponse(false, "AI 请求失败 (" + code + "): " + friendlyError(code, err));
            }

            JSONObject json = new JSONObject(responseBody);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return new AiResponse(false, "AI 返回为空");
            }
            // OpenAI 格式: choices[0].message.content
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) return new AiResponse(false, "AI 返回格式错误");
            JSONObject msgObj = choice.optJSONObject("message");
            if (msgObj == null) return new AiResponse(false, "AI 返回缺少 message 字段");
            String content = msgObj.optString("content", "").trim();
            // 兼容 reasoning 模型（deepseek-reasoner 等）：content 可能为空但有 reasoning_content
            if (content.isEmpty()) {
                content = msgObj.optString("reasoning_content", "").trim();
            }
            if (content.isEmpty()) return new AiResponse(false, "AI 没有返回内容");
            // DESIGN_AGENT_LOOP: 计量 — 优先 usage 字段, 缺失按字符数÷4 估算
            int pt = -1, ct = -1;
            boolean estimated = false;
            JSONObject usage = json.optJSONObject("usage");
            if (usage != null) {
                pt = usage.optInt("prompt_tokens", -1);
                ct = usage.optInt("completion_tokens", -1);
            }
            if (pt < 0) { pt = body.toString().length() / 4; estimated = true; }
            if (ct < 0) { ct = content.length() / 4; estimated = true; }
            return new AiResponse(true, content, pt, ct, estimated);
        } catch (java.net.SocketTimeoutException e) {
            return new AiResponse(false, "AI 请求超时，请检查网络或 API 地址");
        } catch (Exception e) {
            return new AiResponse(false, "AI 调用异常: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private String readStream(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String readErrorStream(HttpURLConnection conn) {
        try {
            if (conn.getErrorStream() == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.trim().isEmpty()) return "无详细信息";
        int idx = text.indexOf('\n');
        if (idx > 0) return text.substring(0, idx).trim();
        return text.trim();
    }

    /**
     * HTTP 错误友好化: 常见状态码给中文提示, 并尽量带上服务端 error.message。
     * 包级 static 以便单测。
     */
    static String friendlyError(int code, String errBody) {
        String hint;
        switch (code) {
            case 400: hint = "请求参数错误"; break;
            case 401: hint = "API Key 无效或已过期"; break;
            case 402: hint = "余额不足，请充值后重试"; break;
            case 403: hint = "无权限访问该模型或接口"; break;
            case 404: hint = "模型不存在或接口地址错误"; break;
            case 429: hint = "请求过于频繁，请稍后再试"; break;
            default:  hint = null;
        }
        String server = extractServerMessage(errBody);
        if (hint != null) {
            // 服务端 message 与提示不重复时附上, 便于定位 (如具体欠费说明)
            return (server != null && !server.isEmpty() && !hint.contains(server))
                    ? hint + " · " + server : hint;
        }
        if (server != null && !server.isEmpty()) return server;
        return firstLine(errBody);
    }

    /** 从 OpenAI 兼容错误体提取 error.message / message, 失败返回 "" */
    static String extractServerMessage(String errBody) {
        try {
            JSONObject j = new JSONObject(errBody);
            JSONObject err = j.optJSONObject("error");
            if (err != null) return err.optString("message", "");
            return j.optString("message", "");
        } catch (Exception e) {
            return "";
        }
    }
}
