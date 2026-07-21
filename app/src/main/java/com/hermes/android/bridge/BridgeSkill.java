package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.skill.SkillStore;

/**
 * 技能 Bridge
 */
public class BridgeSkill extends BaseBridge {

    private final SkillStore store;

    public BridgeSkill(HermesActivity activity) {
        super(activity);
        this.store = activity.getSkillStore();
    }

    public String listSkills() { return store.listSkillsJson(); }
    public String recordSkillUse(String skillId) { return store.recordUse(skillId); }
    public String deleteSkill(String skillId) { return store.deleteSkill(skillId); }
}
