package com.hh.domain.agent.service.armory.matter.skills;

import com.google.adk.skills.SkillSource;

public interface SkillSourceFactory {
    SkillSource createSkillSource(String skillsDir);
}
