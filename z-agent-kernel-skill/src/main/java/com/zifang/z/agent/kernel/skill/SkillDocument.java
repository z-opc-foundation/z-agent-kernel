package com.zifang.z.agent.kernel.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SKILL.md 解析结果 — frontmatter 元信息 + 正文.
 */
public final class SkillDocument {

    private final String name;
    private final String description;
    private final String version;
    private final Map<String, String> metadata;
    private final String body;

    public SkillDocument(String name, String description, String version,
                         Map<String, String> metadata, String body) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.version = version;
        this.metadata = metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
        this.body = body == null ? "" : body;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public String getBody() {
        return body;
    }

    /** @return 组装回给 LLM 看的完整提示词 (frontmatter 摘要 + 正文). */
    public String toPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("## skill: ").append(name);
        if (version != null) {
            sb.append(" (v").append(version).append(')');
        }
        sb.append("\n\n").append(description).append("\n\n").append(body);
        return sb.toString();
    }
}
