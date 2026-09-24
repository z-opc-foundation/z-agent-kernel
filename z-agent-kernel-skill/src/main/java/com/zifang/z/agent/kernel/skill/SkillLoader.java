package com.zifang.z.agent.kernel.skill;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Skill 文档加载 SPI — 从 SKILL.md (YAML frontmatter + Markdown 正文) 装载"程序记忆".
 *
 * <p>语义对齐 hermes skills/ 目录约定: {@code <category>/<skill-name>/SKILL.md},
 * frontmatter 字段 name/description/version/tags. 与 {@link Skill}(带工具的能力包) 不同,
 * SkillDocument 是纯提示词知识, 由 agent 决定何时注入.
 */
public interface SkillLoader {

    /**
     * @param root skills 根目录 (按 {@code <category>/<name>/SKILL.md} 递归扫描)
     * @return 解析出的全部技能文档 (按目录名字典序)
     */
    List<SkillDocument> load(Path root) throws IOException;

    /** 解析单个 SKILL.md. */
    SkillDocument parse(Path skillMd) throws IOException;
}
