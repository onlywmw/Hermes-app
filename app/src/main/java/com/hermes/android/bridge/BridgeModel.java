package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.ai.AiClient;
import com.hermes.android.model.ModelConfig;
import com.hermes.android.model.ModelPresets;
import com.hermes.android.model.ModelRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 多模型注册 Bridge
 */
public class BridgeModel extends BaseBridge {

    private final ModelRegistry registry;

    public BridgeModel(HermesActivity activity) {
        super(activity);
        this.registry = activity.getModelRegistry();
    }

    public String listModels() { return registry.listJson(); }

    /** 厂商预设列表 (不含任何敏感信息), JSON 数组字符串 */
    public String getProviderPresets() {
        try {
            JSONArray arr = new JSONArray();
            for (ModelPresets.Preset p : ModelPresets.all()) {
                JSONObject j = new JSONObject();
                j.put("key", p.key);
                j.put("displayName", p.displayName);
                j.put("baseUrl", p.baseUrl);
                j.put("defaultModel", p.defaultModel);
                j.put("models", new JSONArray());
                for (String m : p.models) j.getJSONArray("models").put(m);
                j.put("keyConsoleUrl", p.keyConsoleUrl);
                j.put("note", p.note);
                arr.put(j);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    public String addModel(String json) {
        try {
            ModelConfig mc = ModelConfig.fromJson(new JSONObject(json));
            String id = registry.add(mc);
            return "{\"ok\":true,\"id\":\"" + id + "\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public String updateModel(String json) {
        try {
            ModelConfig mc = ModelConfig.fromJson(new JSONObject(json));
            /* WebView 编辑路径: listModels 已脱敏, apiKey 留空 = 保留原 Key (防编辑即丢 Key) */
            if (mc.apiKey == null || mc.apiKey.isEmpty()) {
                ModelConfig old = registry.get(mc.id);
                if (old != null) mc.apiKey = old.apiKey;
            }
            /* 掩码 key 回写防护: 脱敏 key (sk-****5678) 被当真 key 写回会静默毁掉配置 */
            if (mc.apiKey != null && mc.apiKey.contains("*")) {
                ModelConfig old = registry.get(mc.id);
                if (old != null) mc.apiKey = old.apiKey;
            }
            boolean ok = registry.update(mc);
            return "{\"ok\":" + ok + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + sanitize(e.getMessage()) + "\"}";
        }
    }

    public String deleteModel(String id) {
        boolean ok = registry.delete(id);
        if (!ok) return "{\"ok\":false,\"error\":\"至少保留一个模型\"}";
        return "{\"ok\":true}";
    }

    public String setDefaultModel(String id) {
        boolean ok = registry.setDefault(id);
        return "{\"ok\":" + ok + "}";
    }

    public String getEncStatus() {
        return "{\"ok\":" + registry.isEncrypted() + "}";
    }

    /** V5: Token 仪表盘数据 (今日/本月/月配额) */
    public String getTokenStats() {
        return com.hermes.android.TokenMeter.statsJson();
    }

    public String testModel(String json) {
        try {
            ModelConfig mc = ModelConfig.fromJson(new JSONObject(json));
            long start = System.currentTimeMillis();
            AiClient client = new AiClient(mc, "回复 OK");
            AiClient.AiResponse resp = client.chat("ping");
            long ms = System.currentTimeMillis() - start;
            if (resp.success) {
                return "{\"ok\":true,\"latencyMs\":" + ms + "}";
            } else {
                return "{\"ok\":false,\"error\":\"" + sanitize(resp.content) + "\"}";
            }
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + sanitize(e.getMessage()) + "\"}";
        }
    }

    /** 结果会原样拼进 evalJs 的 JS 字符串 — 去掉引号/反斜杠/换行防语法注入 */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("\\", " ").replace("\"", " ").replace("'", " ")
                .replace("\n", " ").replace("\r", " ").trim();
    }
}
