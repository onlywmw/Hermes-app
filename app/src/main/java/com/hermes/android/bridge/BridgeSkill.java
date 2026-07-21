package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.skill.SkillStore;

/**
 * P1-1: Skill Bridge — 技能管理
 */
public class BridgeSkill extends BaseBridge {

    private final SkillStore skillStore;

    public BridgeSkill(HermesActivity activity, SkillStore skillStore) {
        super(activity);
        this.skillStore = skillStore;
    }

    public String listSkills() { return skillStore.listSkillsJson(); }
    public String recordSkillUse(String skillId) { return skillStore.recordUse(skillId); }
    public String deleteSkill(String skillId) { return skillStore.deleteSkill(skillId); }
}
