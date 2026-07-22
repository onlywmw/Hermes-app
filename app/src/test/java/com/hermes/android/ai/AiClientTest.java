package com.hermes.android.ai;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * AiClient 错误友好化单元测试 — HTTP 状态码中文提示 + 服务端 message 提取。
 * 纯 Java 测试，不依赖 Android 运行时。
 */
public class AiClientTest {

    @Test public void error402IsFriendly() {
        String body = "{\"error\":{\"code\":\"InsufficientBalance\",\"message\":\"account balance not enough\"}}";
        String r = AiClient.friendlyError(402, body);
        assertTrue(r.startsWith("余额不足"));
        assertTrue(r.contains("account balance not enough")); // 服务端详情保留
    }

    @Test public void error402WithoutBody() {
        assertEquals("余额不足，请充值后重试", AiClient.friendlyError(402, ""));
        assertEquals("余额不足，请充值后重试", AiClient.friendlyError(402, null));
    }

    @Test public void error401IsFriendly() {
        assertEquals("API Key 无效或已过期", AiClient.friendlyError(401, "{}"));
    }

    @Test public void error404IsFriendly() {
        assertEquals("模型不存在或接口地址错误", AiClient.friendlyError(404, "Not Found"));
    }

    @Test public void error429IsFriendly() {
        assertEquals("请求过于频繁，请稍后再试", AiClient.friendlyError(429, ""));
    }

    @Test public void unknownCodeUsesServerMessage() {
        String body = "{\"error\":{\"message\":\"model overloaded\"}}";
        assertEquals("model overloaded", AiClient.friendlyError(500, body));
    }

    @Test public void unknownCodeFallsBackToFirstLine() {
        assertEquals("gateway error", AiClient.friendlyError(502, "gateway error\nstack..."));
        assertEquals("无详细信息", AiClient.friendlyError(502, ""));
    }

    @Test public void extractServerMessageVariants() {
        assertEquals("m1", AiClient.extractServerMessage("{\"error\":{\"message\":\"m1\"}}"));
        assertEquals("m2", AiClient.extractServerMessage("{\"message\":\"m2\"}"));
        assertEquals("", AiClient.extractServerMessage("not json"));
        assertEquals("", AiClient.extractServerMessage(""));
    }
}
