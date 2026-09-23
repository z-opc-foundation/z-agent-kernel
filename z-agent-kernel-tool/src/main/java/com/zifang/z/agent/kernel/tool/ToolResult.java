package com.zifang.z.agent.kernel.tool;

import java.util.Map;

/**
 * 工具调用结果.
 *
 * <p>callId: 关联 ToolCall.id.
 * <p>content: 文本结果.
 * <p>isError: 失败标记.
 * <p>metadata: 结构化结果 (例如表格/对象), 用于上层决定如何渲染.
 */
public final class ToolResult {

    private final String callId;
    private final String name;
    private final String content;
    private final boolean isError;
    private final Map<String, Object> metadata;

    public ToolResult(String callId, String name, String content, boolean isError, Map<String, Object> metadata) {
        this.callId = callId;
        this.name = name;
        this.content = content;
        this.isError = isError;
        this.metadata = metadata;
    }

    public static ToolResult success(String callId, String name, String content) {
        return new ToolResult(callId, name, content, false, null);
    }

    public static ToolResult failure(String callId, String name, String error) {
        return new ToolResult(callId, name, error, true, null);
    }

    /**
     * 工具侧便捷工厂: 不携带 callId/name (由派发方在回灌消息时补齐)。
     */
    public static ToolResult text(String content) {
        return new ToolResult(null, null, content, false, null);
    }

    public static ToolResult error(String error) {
        return new ToolResult(null, null, error, true, null);
    }

    public String getCallId() {
        return callId;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return isError;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}