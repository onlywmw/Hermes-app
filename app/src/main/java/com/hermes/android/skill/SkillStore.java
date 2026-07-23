package com.hermes.android.skill;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * P1-7: 技能存储引擎。
 * 技能 = 可复用的指令模板 + 使用统计。
 * 存储在 SharedPreferences, 支持自动沉淀和手动安装。
 */
public class SkillStore {

    private static final String PREFS = "hermes_skills";
    private static final String KEY_SKILLS = "skills_json";

    private final SharedPreferences prefs;

    public SkillStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        seedDefaults();
    }

    /** 首次安装预置示例技能 */
    private void seedDefaults() {
        if (prefs.contains(KEY_SKILLS)) return;
        try {
            JSONArray skills = new JSONArray();

            skills.put(new JSONObject()
                    .put("id", "proxy-node-live-test")
                    .put("name", "proxy-node-live-test")
                    .put("desc", "用本地内核实测代理节点真实连通性, 区分节点失效与客户端配置问题")
                    .put("source", "自动生成")
                    .put("status", "稳定")
                    .put("revisions", 0)
                    .put("uses", 0)
                    .put("lastUsed", 0));

            skills.put(new JSONObject()
                    .put("id", "image-person-segmenter")
                    .put("name", "image-person-segmenter")
                    .put("desc", "图片人物/物体拆分与抠图, 支持绿幕与 AI 智能抠图双模式")
                    .put("source", "用户安装")
                    .put("status", "稳定")
                    .put("revisions", 0)
                    .put("uses", 0)
                    .put("lastUsed", 0));

            skills.put(new JSONObject()
                    .put("id", "kb-grow")
                    .put("name", "kb-grow")
                    .put("desc", "知识库摄入 → 自动构建 Wiki → 智能查询, 触发词 /ingest /buildkb")
                    .put("source", "自动生成")
                    .put("status", "稳定")
                    .put("revisions", 0)
                    .put("uses", 0)
                    .put("lastUsed", 0));

            prefs.edit().putString(KEY_SKILLS, skills.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** 列出所有技能 JSON */
    public String listSkillsJson() {
        return prefs.getString(KEY_SKILLS, "[]");
    }

    /** 记录技能使用 */
    public String recordUse(String skillId) {
        try {
            JSONArray skills = new JSONArray(prefs.getString(KEY_SKILLS, "[]"));
            for (int i = 0; i < skills.length(); i++) {
                JSONObject skill = skills.getJSONObject(i);
                if (skillId.equals(skill.getString("id"))) {
                    skill.put("uses", skill.getInt("uses") + 1);
                    skill.put("lastUsed", System.currentTimeMillis());
                    prefs.edit().putString(KEY_SKILLS, skills.toString()).apply();
                    return new JSONObject().put("ok", true).toString();
                }
            }
            return "{\"ok\":false,\"error\":\"技能不存在\"}";
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 删除技能 */
    public String deleteSkill(String skillId) {
        try {
            JSONArray skills = new JSONArray(prefs.getString(KEY_SKILLS, "[]"));
            JSONArray filtered = new JSONArray();
            boolean found = false;
            for (int i = 0; i < skills.length(); i++) {
                JSONObject skill = skills.getJSONObject(i);
                if (skillId.equals(skill.getString("id"))) {
                    found = true;
                } else {
                    filtered.put(skill);
                }
            }
            if (!found) return "{\"ok\":false,\"error\":\"技能不存在\"}";
            prefs.edit().putString(KEY_SKILLS, filtered.toString()).apply();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** JSONObject 构造错误 JSON (自动转义), 与 StorageManager.errJson 同模式 */
    private String errJson(Exception e) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", e.getMessage() != null ? e.getMessage() : "未知错误").toString();
        } catch (Exception ex) {
            return "{\"ok\":false}";
        }
    }
}
