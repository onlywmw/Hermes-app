package com.hermes.android.agent;

import org.json.JSONObject;

/**
 * AgentLoop 动作解析器 — 从大脑( LLM )输出中容错提取一个 JSON 动作。
 *
 * 容错策略 (DESIGN_AGENT_LOOP 工具协议):
 * 1. 剥 markdown ```json / ``` 围栏
 * 2. 取首个 '{' 到末个 '}' 的子串
 * 3. 解析失败返回 err (供回灌重试, 不计为合法动作)
 */
public final class ActionParser {

    private ActionParser() {}

    /** 解析结果: ok 时持有 action JSONObject; 否则持有 err 信息 */
    public static class Result {
        public final boolean ok;
        public final JSONObject action;
        public final String err;

        private Result(boolean ok, JSONObject action, String err) {
            this.ok = ok;
            this.action = action;
            this.err = err;
        }
    }

    /** 提取文本中的 JSON 对象子串 (剥围栏 + 首个{到末个}); 找不到返回 null */
    public static String extractJson(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    public static Result parse(String text) {
        String json = extractJson(text);
        if (json == null) return new Result(false, null, "未找到 JSON 对象");
        try {
            JSONObject obj = new JSONObject(json);
            String action = obj.optString("action", "");
            if (action.isEmpty()) return new Result(false, null, "缺少 action 字段");
            return new Result(true, obj, null);
        } catch (Exception e) {
            return new Result(false, null, "JSON 解析失败: " + e.getMessage());
        }
    }
}
