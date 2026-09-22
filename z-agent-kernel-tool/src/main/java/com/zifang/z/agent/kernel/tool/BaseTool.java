package com.zifang.z.agent.kernel.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for kernel.tools.Tool implementations.
 *
 * <p>用法: 子类只实现 {@link #doExecute(Map)}, 通过 {@link #getSchema()} 暴露 JSON Schema 给 LLM.
 *
 * <p>对应 z-opc 老 BaseTool (distilled from z-agent-engine).
 */
public abstract class BaseTool implements Tool {

    private final String name;
    private final String description;
    private final Map<String, Object> schema;

    protected BaseTool(String name, String description) {
        this(name, description, null);
    }

    protected BaseTool(String name, String description, Map<String, Object> schema) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("tool name required");
        }
        this.name = name;
        this.description = description == null ? "" : description;
        this.schema = schema == null ? new HashMap<String, Object>() : schema;
    }

    /**
     * 子类实现具体的工具逻辑.
     *
     * @param args LLM 传入的 JSON 参数
     * @return 工具执行结果(文本或结构化)
     */
    protected abstract ToolResult doExecute(Map<String, Object> args);

    @Override
    public final ToolResult execute(Map<String, Object> arguments) {
        return doExecute(arguments == null ? new HashMap<String, Object>() : arguments);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getSchema() {
        return schema;
    }

    @Override
    public String toString() {
        return "Tool{" + name + "}";
    }
}