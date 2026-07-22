package com.hermes.android.model;

import org.json.JSONObject;

/**
 * 单个 AI 模型配置。
 * 每个模型独立: name / provider / baseUrl / apiKey / model / role / color。
 */
public class ModelConfig {

    public String id;           // "deepseek_v4"
    public String name;         // "DeepSeek V4"
    public String provider;     // ModelPresets 的 key: "deepseek" | "moonshot" | "zhipu" | "qwen" | "doubao" | "spark" | "minimax" | "baichuan" | "stepfun" | "hunyuan" | "yi" | "openai" | "ollama"
    public String baseUrl;      // 空走默认
    public String apiKey;       // 加密存储
    public String model;        // "deepseek-v4-flash"
    public String systemPrompt; // 可覆盖, 空走默认
    public String role;         // "通用" | "产品" | "技术" | "数据" | "自定义"
    public String color;        // 头像色 "#D97706"
    public boolean enabled;
    public boolean isDefault;   // 单聊模式用的默认模型

    public ModelConfig() {
        enabled = true;
        isDefault = false;
        role = "通用";
        color = "#D97706";
    }

    /** 从 JSON 反序列化 */
    public static ModelConfig fromJson(JSONObject j) {
        ModelConfig c = new ModelConfig();
        c.id = j.optString("id", "");
        c.name = j.optString("name", "");
        c.provider = j.optString("provider", "deepseek");
        c.baseUrl = j.optString("baseUrl", "");
        c.apiKey = j.optString("apiKey", "");
        c.model = j.optString("model", "");
        c.systemPrompt = j.optString("systemPrompt", "");
        c.role = j.optString("role", "通用");
        c.color = j.optString("color", "#D97706");
        c.enabled = j.optBoolean("enabled", true);
        c.isDefault = j.optBoolean("isDefault", false);
        return c;
    }

    /** 序列化为 JSON (apiKey 可选脱敏) */
    public JSONObject toJson() {
        return toJson(false);
    }

    public JSONObject toJson(boolean maskKey) {
        try {
            JSONObject j = new JSONObject();
            j.put("id", id);
            j.put("name", name);
            j.put("provider", provider);
            j.put("baseUrl", baseUrl);
            j.put("apiKey", maskKey ? maskKey(apiKey) : apiKey);
            j.put("model", model);
            j.put("systemPrompt", systemPrompt);
            j.put("role", role);
            j.put("color", color);
            j.put("enabled", enabled);
            j.put("isDefault", isDefault);
            return j;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 是否已配置 (ollama 不需要 key) */
    public boolean isConfigured() {
        if ("ollama".equals(provider)) return !getEffectiveBaseUrl().isEmpty();
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** 有效 baseUrl (空走 ModelPresets 中 provider 的默认) */
    public String getEffectiveBaseUrl() {
        if (baseUrl != null && !baseUrl.trim().isEmpty()) return baseUrl.trim();
        return ModelPresets.defaultBaseUrl(provider);
    }

    /** 有效 model (空走 ModelPresets 中 provider 的默认) */
    public String getEffectiveModel() {
        if (model != null && !model.trim().isEmpty()) return model.trim();
        return ModelPresets.defaultModel(provider);
    }

    /** provider 显示名 (走 ModelPresets, 未知 key 原样返回) */
    public String getProviderDisplayName() {
        return ModelPresets.displayName(provider);
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return key == null ? "" : "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
