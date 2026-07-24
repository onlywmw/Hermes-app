package com.hermes.android.model;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** ModelConfig 序列化: isReviewer 评审身份标记的读写与兼容 */
public class ModelConfigTest {

    @Test
    public void jsonRoundTrip_keepsIsReviewer() throws Exception {
        ModelConfig c = new ModelConfig();
        c.id = "t1";
        c.name = "T";
        c.provider = "deepseek";
        c.apiKey = "sk-x";
        c.isReviewer = true;
        ModelConfig back = ModelConfig.fromJson(c.toJson());
        assertTrue(back.isReviewer);
        assertFalse(back.isDefault);
    }

    @Test
    public void fromJson_oldDataWithoutReviewer_defaultsFalse() {
        /* 旧版持久化数据无 isReviewer 字段 → 默认 false, 不得炸 */
        ModelConfig c = ModelConfig.fromJson(new JSONObject());
        assertFalse(c.isReviewer);
    }

    @Test
    public void toJson_maskedVariantAlsoCarriesReviewer() {
        ModelConfig c = new ModelConfig();
        c.isReviewer = true;
        assertTrue(c.toJson(true).optBoolean("isReviewer", false));
    }
}
