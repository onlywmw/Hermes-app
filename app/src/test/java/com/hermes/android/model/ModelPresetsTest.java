package com.hermes.android.model;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ModelPresets 单元测试 — 14 个预设齐全、默认值、边界输入。
 * 纯 Java 测试，不依赖 Android 运行时。
 */
public class ModelPresetsTest {

    private static final String[] EXPECTED_KEYS = {
        "deepseek", "moonshot", "zhipu", "qwen", "doubao", "spark", "minimax",
        "baichuan", "stepfun", "mimo", "hunyuan", "yi", "openai", "ollama"
    };

    // ==================== 预设齐全 ====================
    @Test public void allReturns14PresetsInOrder() {
        ModelPresets.Preset[] all = ModelPresets.all();
        assertNotNull(all);
        assertEquals(14, all.length);
        for (int i = 0; i < EXPECTED_KEYS.length; i++) {
            assertEquals(EXPECTED_KEYS[i], all[i].key);
        }
    }

    @Test public void everyPresetIsComplete() {
        for (ModelPresets.Preset p : ModelPresets.all()) {
            assertFalse(p.key.isEmpty());
            assertFalse(p.displayName.isEmpty());
            assertTrue(p.baseUrl.startsWith("http"));
            assertFalse(p.defaultModel.isEmpty());
            assertNotNull(p.models);
            assertTrue(p.models.length >= 1);
            // 默认款在 models 第一个
            assertEquals(p.defaultModel, p.models[0]);
            // keyConsoleUrl / note 不为 null (ollama 允许空串)
            assertNotNull(p.keyConsoleUrl);
            assertNotNull(p.note);
        }
    }

    @Test public void keysAreUnique() {
        ModelPresets.Preset[] all = ModelPresets.all();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertNotEquals(all[i].key, all[j].key);
            }
        }
    }

    // ==================== deepseek 默认值 ====================
    @Test public void deepseekDefaults() {
        ModelPresets.Preset p = ModelPresets.get("deepseek");
        assertNotNull(p);
        assertEquals("DeepSeek", p.displayName);
        assertEquals("https://api.deepseek.com/v1", p.baseUrl);
        assertEquals("deepseek-v4-flash", p.defaultModel);
        assertEquals("deepseek-v4-pro", p.models[1]); // 旗舰放第二个
        assertEquals("https://platform.deepseek.com/api_keys", p.keyConsoleUrl);
    }

    // ==================== 厂商特例 ====================
    @Test public void zhipuBaseUrlHasNoV1Suffix() {
        ModelPresets.Preset p = ModelPresets.get("zhipu");
        assertNotNull(p);
        assertEquals("https://open.bigmodel.cn/api/paas/v4", p.baseUrl);
        assertFalse(p.baseUrl.endsWith("/v1"));
    }

    @Test public void ollamaHasNoKeyConsoleUrl() {
        ModelPresets.Preset p = ModelPresets.get("ollama");
        assertNotNull(p);
        assertEquals("", p.keyConsoleUrl);
        assertEquals("http://192.168.1.100:11434/v1", p.baseUrl);
    }

    // ==================== 小米 MiMo (第 14 家) ====================
    @Test public void mimoPreset() {
        ModelPresets.Preset p = ModelPresets.get("mimo");
        assertNotNull(p);
        assertEquals("小米 MiMo", p.displayName);
        assertEquals("https://api.xiaomimimo.com/v1", p.baseUrl);
        assertEquals("mimo-v2.5", p.defaultModel);
        assertEquals(3, p.models.length);
        assertEquals("mimo-v2.5-pro", p.models[1]); // 旗舰放第二个
        assertEquals("mimo-v2.5-pro-ultraspeed", p.models[2]);
        assertEquals("https://platform.xiaomimimo.com", p.keyConsoleUrl);
        assertTrue(p.note.contains("tp-"));
    }

    @Test public void legacyProvidersUnchanged() {
        // 存量用户的 4 个 provider key 行为不变
        assertEquals("https://api.openai.com/v1", ModelPresets.defaultBaseUrl("openai"));
        assertEquals("gpt-4o-mini", ModelPresets.defaultModel("openai"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", ModelPresets.defaultBaseUrl("qwen"));
        assertEquals("http://192.168.1.100:11434/v1", ModelPresets.defaultBaseUrl("ollama"));
        assertEquals("llama3", ModelPresets.defaultModel("ollama"));
        assertEquals("通义千问", ModelPresets.displayName("qwen"));
    }

    // ==================== 边界输入不崩 ====================
    @Test public void getNullReturnsNull() {
        assertNull(ModelPresets.get(null));
    }

    @Test public void getUnknownReturnsNull() {
        assertNull(ModelPresets.get("xx"));
        assertNull(ModelPresets.get(""));
        assertNull(ModelPresets.get("DEEPSEEK")); // 大小写敏感
    }

    @Test public void unknownFallsBackToDeepseek() {
        assertEquals("https://api.deepseek.com/v1", ModelPresets.defaultBaseUrl(null));
        assertEquals("https://api.deepseek.com/v1", ModelPresets.defaultBaseUrl("xx"));
        assertEquals("deepseek-v4-flash", ModelPresets.defaultModel(null));
        assertEquals("deepseek-v4-flash", ModelPresets.defaultModel("xx"));
    }

    @Test public void unknownDisplayNameReturnsKeyItself() {
        assertEquals("xx", ModelPresets.displayName("xx"));
        assertEquals("Kimi", ModelPresets.displayName("moonshot"));
    }
}
