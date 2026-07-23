package com.hermes.android.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import com.hermes.android.ai.AiProviderConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 多模型注册中心。
 * 存储: EncryptedSharedPreferences "mov_models" → JSONArray
 * 至少保留一个模型。
 * 首次启动自动从旧 AiProviderConfig 迁移。
 * 应用级单例: 通过 getInstance(context) 获取, 避免多实例数据互相覆盖。
 */
public class ModelRegistry {

    private static final String TAG = "ModelRegistry";
    private static final String PREFS = "mov_models";
    private static final String KEY_MODELS = "models";

    private static ModelRegistry instance;

    /** 应用级单例, 静态持有 applicationContext 防泄漏 */
    public static synchronized ModelRegistry getInstance(Context context) {
        if (instance == null) {
            instance = new ModelRegistry(context.getApplicationContext());
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final boolean encrypted;
    private final List<ModelConfig> models = new ArrayList<>();

    public ModelRegistry(Context context) {
        SharedPreferences encrypted = null;
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            encrypted = EncryptedSharedPreferences.create(
                    context, PREFS, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.w(TAG, "加密存储不可用, 降级明文: " + e.getMessage());
        }
        prefs = encrypted != null ? encrypted
                : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.encrypted = (encrypted != null);
        load();
        if (models.isEmpty()) {
            migrateFromLegacy(context);
        }
    }

    /* ── CRUD (全部 synchronized: 读在 aiExecutor 线程, 写在 JavaBridge 线程) ── */

    public synchronized List<ModelConfig> list() {
        return new ArrayList<>(models);
    }

    public synchronized ModelConfig get(String id) {
        for (ModelConfig m : models) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    public synchronized ModelConfig getDefault() {
        for (ModelConfig m : models) {
            if (m.isDefault && m.enabled) return m;
        }
        // 没有默认 → 返回第一个 enabled 的
        for (ModelConfig m : models) {
            if (m.enabled) return m;
        }
        return models.isEmpty() ? null : models.get(0);
    }

    public synchronized String add(ModelConfig config) {
        if (config.id == null || config.id.isEmpty()) {
            config.id = UUID.randomUUID().toString().substring(0, 8);
        }
        // 第一个模型自动设为默认
        if (models.isEmpty()) config.isDefault = true;
        models.add(config);
        save();
        return config.id;
    }

    public synchronized boolean update(ModelConfig config) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id.equals(config.id)) {
                models.set(i, config);
                save();
                return true;
            }
        }
        return false;
    }

    /** 删除模型, 至少保留一个 */
    public synchronized boolean delete(String id) {
        if (models.size() <= 1) return false;
        boolean removed = models.removeIf(m -> m.id.equals(id));
        if (removed) {
            // 如果删的是默认, 把第一个设为默认
            boolean hasDefault = false;
            for (ModelConfig m : models) {
                if (m.isDefault) { hasDefault = true; break; }
            }
            if (!hasDefault && !models.isEmpty()) {
                models.get(0).isDefault = true;
            }
            save();
        }
        return removed;
    }

    public synchronized boolean setDefault(String id) {
        boolean found = false;
        for (ModelConfig m : models) {
            if (m.id.equals(id)) {
                m.isDefault = true;
                found = true;
            } else {
                m.isDefault = false;
            }
        }
        if (found) save();
        return found;
    }

    /* ── 序列化 ── */

    public synchronized String listJson() {
        try {
            JSONArray arr = new JSONArray();
            for (ModelConfig m : models) {
                arr.put(m.toJson(true)); // apiKey 脱敏
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 加密存储是否可用 (降级明文时弹警告) */
    public boolean isEncrypted() { return encrypted; }

    /* ── 持久化 ── */

    private synchronized void load() {
        models.clear();
        String json = prefs.getString(KEY_MODELS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                models.add(ModelConfig.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            Log.e(TAG, "加载模型列表失败: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            for (ModelConfig m : models) {
                arr.put(m.toJson(false));
            }
            prefs.edit().putString(KEY_MODELS, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "保存模型列表失败: " + e.getMessage());
        }
    }

    /* ── 从旧 AiProviderConfig 迁移 ── */

    private synchronized void migrateFromLegacy(Context context) {
        try {
            // 旧加密 prefs 的 key 本身已加密, 必须用 EncryptedSharedPreferences 读,
            // 复用 AiProviderConfig 的工厂方法; Keystore 异常时返回 null, 走明文回落
            String apiKey = "";
            String provider = "deepseek";
            String model = "";
            String baseUrl = "";
            String sysPrompt = "";

            SharedPreferences old = AiProviderConfig.openEncryptedPrefs(context);
            if (old != null) {
                apiKey = old.getString("api_key", "");
                provider = old.getString("provider", "deepseek");
                model = old.getString("model", "");
                baseUrl = old.getString("base_url", "");
                sysPrompt = old.getString("system_prompt", "");
            }

            if (apiKey == null || apiKey.trim().isEmpty()) {
                // 试明文 prefs (若已被 AiProviderConfig 迁移并 clear, 读到空, 下面会跳过)
                old = context.getSharedPreferences("hermes_ai_prefs", Context.MODE_PRIVATE);
                apiKey = old.getString("api_key", "");
                provider = old.getString("provider", "deepseek");
                model = old.getString("model", "");
                baseUrl = old.getString("base_url", "");
                sysPrompt = old.getString("system_prompt", "");
            }

            if (apiKey == null || apiKey.trim().isEmpty()) {
                Log.i(TAG, "旧配置为空或已迁移, 跳过");
                return;
            }

            ModelConfig legacy = new ModelConfig();
            legacy.id = "legacy_" + provider;
            legacy.name = providerName(provider);
            legacy.provider = provider;
            legacy.baseUrl = baseUrl != null ? baseUrl : "";
            legacy.apiKey = apiKey != null ? apiKey : "";
            legacy.model = model != null ? model : "";
            legacy.systemPrompt = sysPrompt != null ? sysPrompt : "";
            legacy.isDefault = true;
            legacy.enabled = true;
            legacy.role = "通用";
            legacy.color = "#D97706";

            models.add(legacy);
            save();
            Log.i(TAG, "已从旧 AiProviderConfig 迁移 1 个模型: " + legacy.name);
        } catch (Exception e) {
            Log.w(TAG, "迁移失败: " + e.getMessage());
        }
    }

    private static String providerName(String provider) {
        switch (provider) {
            case "openai":   return "OpenAI";
            case "deepseek": return "DeepSeek";
            case "qwen":     return "通义千问";
            case "ollama":   return "Ollama";
            default:         return provider;
        }
    }
}
