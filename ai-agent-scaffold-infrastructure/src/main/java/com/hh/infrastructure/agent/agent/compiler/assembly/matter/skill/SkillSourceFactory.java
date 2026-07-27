package com.hh.infrastructure.agent.agent.compiler.assembly.matter.skill;

import com.google.adk.skills.SkillSource;

public interface SkillSourceFactory {
    SkillSource createSkillSource(String skillsDir);
}
