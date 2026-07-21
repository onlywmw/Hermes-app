package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.ai.AiClient;
import com.hermes.android.model.ModelConfig;
import com.hermes.android.model.ModelRegistry;

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
            boolean ok = registry.update(mc);
            return "{\"ok\":" + ok + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
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
                return "{\"ok\":false,\"error\":\"" + resp.content.replace("\"", "'") + "\"}";
            }
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
