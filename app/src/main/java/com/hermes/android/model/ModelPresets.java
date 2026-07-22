package com.hermes.android.model;

/**
 * 厂商预设表 — 13 家 OpenAI 兼容厂商的 baseUrl / 默认模型 / 旗舰模型 / 获取 Key 页面。
 * 单一数据源: ModelConfig 的空值默认、设置页的自动填充、JS 侧的厂商列表都从这里取。
 * 纯 Java, 不依赖 Android, 可直接单测。
 */
public class ModelPresets {

    public static class Preset {
        public String key;           // "deepseek"
        public String displayName;   // "DeepSeek"
        public String baseUrl;       // 完整 baseUrl
        public String defaultModel;  // 默认模型 ID
        public String[] models;      // 推荐模型列表（默认款在第一个）
        public String keyConsoleUrl; // 获取 API Key 页面, ollama 为空串
        public String note;          // 一句话提示(如 "需先充值"), 可为空串
    }

    private static Preset p(String key, String displayName, String baseUrl,
                            String defaultModel, String[] models,
                            String keyConsoleUrl, String note) {
        Preset p = new Preset();
        p.key = key;
        p.displayName = displayName;
        p.baseUrl = baseUrl;
        p.defaultModel = defaultModel;
        p.models = models;
        p.keyConsoleUrl = keyConsoleUrl;
        p.note = note;
        return p;
    }

    private static final Preset[] PRESETS = {
        p("deepseek", "DeepSeek",
            "https://api.deepseek.com/v1",
            "deepseek-v4-flash",
            new String[]{"deepseek-v4-flash", "deepseek-v4-pro", "deepseek-reasoner"},
            "https://platform.deepseek.com/api_keys",
            "需先充值；旧名 deepseek-chat 已停用"),
        p("moonshot", "Kimi",
            "https://api.moonshot.cn/v1",
            "kimi-k2.6",
            new String[]{"kimi-k2.6", "kimi-k3", "kimi-latest"},
            "https://platform.moonshot.cn/console/api-keys",
            ""),
        p("zhipu", "智谱 GLM",
            "https://open.bigmodel.cn/api/paas/v4",
            "glm-4.7-flash",
            new String[]{"glm-4.7-flash", "glm-5.2", "glm-4.7"},
            "https://bigmodel.cn/usercenter/proj-mgmt/apikeys",
            "注意无 /v1 后缀；glm-4.7-flash 免费"),
        p("qwen", "通义千问",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen3.7-plus",
            new String[]{"qwen3.7-plus", "qwen3.7-max", "qwen3.7-turbo"},
            "https://bailian.console.aliyun.com/",
            "需先开通百炼"),
        p("doubao", "豆包",
            "https://ark.cn-beijing.volces.com/api/v3",
            "doubao-seed-2-0-mini-260428",
            new String[]{"doubao-seed-2-0-mini-260428", "doubao-seed-2-1-pro-260628",
                "doubao-seed-2-0-flash-260428"},
            "https://console.volcengine.com/ark/region:ark+cn-beijing/apikey",
            "需实名+开通模型服务"),
        p("spark", "讯飞星火",
            "https://spark-api-open.xf-yun.com/v1",
            "generalv3.5",
            new String[]{"generalv3.5", "4.0Ultra", "lite"},
            "https://console.xfyun.cn/services/bm35",
            "填的是 APIPassword 不是 APIKey；lite 免费"),
        p("minimax", "MiniMax",
            "https://api.minimaxi.com/v1",
            "MiniMax-M2.5",
            new String[]{"MiniMax-M2.5", "MiniMax-M3", "MiniMax-M2"},
            "https://platform.minimaxi.com/user-center/basic-information/interface-key",
            "模型 ID 大小写敏感"),
        p("baichuan", "百川智能",
            "https://api.baichuan-ai.com/v1",
            "Baichuan4-Turbo",
            new String[]{"Baichuan4-Turbo", "Baichuan4", "Baichuan3-Turbo"},
            "https://platform.baichuan-ai.com/console/apikey",
            "需实名"),
        p("stepfun", "阶跃星辰",
            "https://api.stepfun.com/v1",
            "step-3.5-flash",
            new String[]{"step-3.5-flash", "step-3.7-flash", "step-3"},
            "https://platform.stepfun.com/interface-key",
            ""),
        p("hunyuan", "腾讯混元",
            "https://tokenhub.tencentmaas.com/v1",
            "hy3",
            new String[]{"hy3", "hy3-pro"},
            "https://console.cloud.tencent.com/tokenhub",
            "新平台 TokenHub，旧端点已停售"),
        p("yi", "零一万物",
            "https://api.lingyiwanwu.com/v1",
            "yi-lightning",
            new String[]{"yi-lightning", "yi-large", "yi-medium"},
            "https://platform.lingyiwanwu.com/apikeys",
            "平台战略转向 ToB，维护停滞"),
        p("openai", "OpenAI",
            "https://api.openai.com/v1",
            "gpt-4o-mini",
            new String[]{"gpt-4o-mini", "gpt-4o", "gpt-4.1"},
            "https://platform.openai.com/api-keys",
            ""),
        p("ollama", "Ollama",
            "http://192.168.1.100:11434/v1",
            "llama3",
            new String[]{"llama3", "qwen3", "deepseek-r1"},
            "",
            "本地模型无需 Key"),
    };

    /** 全部预设, 顺序固定 (deepseek 在前, ollama 兜底) */
    public static Preset[] all() {
        return PRESETS;
    }

    /** 按 key 查预设, 找不到返回 null */
    public static Preset get(String key) {
        if (key == null) return null;
        for (Preset p : PRESETS) {
            if (p.key.equals(key)) return p;
        }
        return null;
    }

    /** 默认 baseUrl, 找不到返回 deepseek 的 */
    public static String defaultBaseUrl(String key) {
        Preset p = get(key);
        return p != null ? p.baseUrl : PRESETS[0].baseUrl;
    }

    /** 默认 model, 找不到返回 deepseek 的 */
    public static String defaultModel(String key) {
        Preset p = get(key);
        return p != null ? p.defaultModel : PRESETS[0].defaultModel;
    }

    /** provider 显示名, 找不到返回 key 本身 */
    public static String displayName(String key) {
        Preset p = get(key);
        return p != null ? p.displayName : key;
    }
}
