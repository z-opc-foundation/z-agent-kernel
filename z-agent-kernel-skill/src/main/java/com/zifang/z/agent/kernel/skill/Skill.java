package com.zifang.z.agent.kernel.skill;

import java.util.Map;

/**
 * Skill SPI — 可被 LLM 调用的能力包.
 *
 * <p>实现方: z-skill (Skill 注册中心 + Marketplace).
 *
 * <p>Skill 由 frontmatter + 一组工具/工作流组成, agent 在对话中按需加载.
 */
public interface Skill {

    /**
     * @return skill 唯一名(如 "code-review" / "sql-query")
     */
    String name();

    /**
     * @return skill 简介(给 LLM 看的)
     */
    String description();

    /**
     * @return skill 暴露的工具列表, agent 会按需注入到 LLM tools 字段
     */
    Map<String, ? extends com.zifang.z.agent.kernel.tool.Tool> tools();

    /**
     * @return skill 版本(语义化版本字符串, 用于 Marketplace 升级判定)
     */
    String version();

    /**
     * 可选: skill 触发条件(自然语言描述, 由 agent 决定是否加载该 skill).
     * 例: "当用户请求 SQL 查询或数据分析时加载"
     */
    default String trigger() {
        return null;
    }
}