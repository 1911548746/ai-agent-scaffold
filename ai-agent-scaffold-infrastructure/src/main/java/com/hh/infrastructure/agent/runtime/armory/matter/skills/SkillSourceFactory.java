package com.hh.infrastructure.agent.runtime.armory.matter.skills;

import com.google.adk.skills.SkillSource;

public interface SkillSourceFactory {
    SkillSource createSkillSource(String skillsDir);
}
