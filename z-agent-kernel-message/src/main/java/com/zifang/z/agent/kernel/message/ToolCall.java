package com.zifang.z.agent.kernel.message;

import java.util.Collections;
import java.util.Map;

/**
 * LLM 工具调用. 由 assistant 消息发出, 由 user/function 消息回执.
 *
 * <p>id: LLM 分配的工具调用 id, 用于关联 tool_result.
 * <p>name: 工具函数名.
 * <p>argumentsJson: 参数(JSON 字符串, 避免提前解析).
 */
public final class ToolCall {

    private final String id;
    private final String name;
    private final String argumentsJson;
    private final Map<String, Object> metadata;

    public ToolCall(String id, String name, String argumentsJson) {
        this(id, name, argumentsJson, Collections.emptyMap());
    }

    public ToolCall(String id, String name, String argumentsJson, Map<String, Object> metadata) {
        this.id = id;
        this.name = name;
        this.argumentsJson = argumentsJson;
        this.metadata = metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}