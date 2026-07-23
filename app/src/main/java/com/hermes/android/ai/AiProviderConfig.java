package com.hermes.android.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.Map;

/**
 * AI provider configuration.
 * P0-4: API Key 使用 EncryptedSharedPreferences 加密存储,
 *       Keystore 不可用时降级为普通 SharedPreferences。
 */
public class AiProviderConfig {

    private static final String TAG = "AiProviderConfig";
    private static final String PREFS = "hermes_ai_prefs";
    private static final String PREFS_ENC = "hermes_ai_prefs_enc";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_AI_ENABLED = "ai_enabled";
    private static final String KEY_LANGUAGE = "language";

    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_QWEN = "qwen";
    public static final String PROVIDER_OLLAMA = "ollama";

    private final SharedPreferences prefs;

    /** 创建旧版加密 prefs 实例; Keystore 异常时返回 null (不崩, 调用方自行兜底) */
    public static SharedPreferences openEncryptedPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context, PREFS_ENC, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.w(TAG, "EncryptedSharedPreferences 不可用, 降级明文存储: " + e.getMessage());
            return null;
        }
    }

    public AiProviderConfig(Context context) {
        SharedPreferences encrypted = openEncryptedPrefs(context);

        if (encrypted != null) {
            migrateIfNeeded(context, encrypted);
            prefs = encrypted;
        } else {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    /** 从旧明文 prefs 迁移到加密 prefs (仅一次) */
    private void migrateIfNeeded(Context context, SharedPreferences encrypted) {
        SharedPreferences old = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!old.contains(KEY_API_KEY) || encrypted.contains(KEY_API_KEY)) return;
        SharedPreferences.Editor ed = encrypted.edit();
        for (Map.Entry<String, ?> entry : old.getAll().entrySet()) {
            Object v = entry.getValue();
            if (v instanceof String) ed.putString(entry.getKey(), (String) v);
            else if (v instanceof Boolean) ed.putBoolean(entry.getKey(), (Boolean) v);
            else if (v instanceof Integer) ed.putInt(entry.getKey(), (Integer) v);
            else if (v instanceof Long) ed.putLong(entry.getKey(), (Long) v);
            else if (v instanceof Float) ed.putFloat(entry.getKey(), (Float) v);
        }
        ed.apply();
        old.edit().clear().apply();
        Log.i(TAG, "已从明文 prefs 迁移到加密存储");
    }

    public String getProvider() {
        return prefs.getString(KEY_PROVIDER, PROVIDER_DEEPSEEK);
    }

    public void setProvider(String provider) {
        prefs.edit().putString(KEY_PROVIDER, provider).apply();
    }

    public String getBaseUrl() {
        String custom = prefs.getString(KEY_BASE_URL, "");
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        switch (getProvider()) {
            case PROVIDER_OPENAI:   return "https://api.openai.com/v1";
            case PROVIDER_DEEPSEEK: return "https://api.deepseek.com/v1";
            case PROVIDER_QWEN:     return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case PROVIDER_OLLAMA:   return "http://192.168.1.100:11434/v1";
            default:                return "https://api.deepseek.com/v1";
        }
    }

    public void setBaseUrl(String url) {
        prefs.edit().putString(KEY_BASE_URL, url != null ? url.trim() : "").apply();
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API_KEY, key != null ? key.trim() : "").apply();
    }

    public String getModel() {
        String custom = prefs.getString(KEY_MODEL, "");
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        switch (getProvider()) {
            case PROVIDER_OPENAI:   return "gpt-4o-mini";
            case PROVIDER_DEEPSEEK: return "deepseek-v4-flash";
            case PROVIDER_QWEN:     return "qwen-plus";
            case PROVIDER_OLLAMA:   return "llama3";
            default:                return "deepseek-v4-flash";
        }
    }

    public void setModel(String model) {
        prefs.edit().putString(KEY_MODEL, model != null ? model.trim() : "").apply();
    }

    public String getSystemPrompt() {
        String custom = prefs.getString(KEY_SYSTEM_PROMPT, "");
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        return "你是 MOV，一个运行在 Android 平板上的多模型协作工作台。你简洁、有用、偶尔幽默。回答用中文。";
    }

    public void setSystemPrompt(String prompt) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt != null ? prompt.trim() : "").apply();
    }

    public boolean isAiEnabled() {
        return prefs.getBoolean(KEY_AI_ENABLED, true);
    }

    public void setAiEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AI_ENABLED, enabled).apply();
    }

    public boolean isConfigured() {
        String provider = getProvider();
        if (PROVIDER_OLLAMA.equals(provider)) {
            return !getBaseUrl().isEmpty();
        }
        return !getApiKey().isEmpty();
    }

    public String getProviderDisplayName() {
        switch (getProvider()) {
            case PROVIDER_OPENAI:   return "OpenAI 兼容";
            case PROVIDER_DEEPSEEK: return "DeepSeek";
            case PROVIDER_QWEN:     return "通义千问";
            case PROVIDER_OLLAMA:   return "本地 Ollama";
            default:                return getProvider();
        }
    }

    public String getStatusSummary() {
        if (!isAiEnabled()) return "AI 已关闭";
        if (!isConfigured()) return "AI 未配置";
        return getProviderDisplayName() + " · " + getModel();
    }

    public void applyPreset(String provider) {
        setProvider(provider);
        setBaseUrl("");
        setModel("");
    }

    /* 界面语言: zh (默认) / en */
    public String getLanguage() {
        return prefs.getString(KEY_LANGUAGE, "zh");
    }

    public void setLanguage(String lang) {
        prefs.edit().putString(KEY_LANGUAGE, lang != null ? lang.trim() : "zh").apply();
    }
}
