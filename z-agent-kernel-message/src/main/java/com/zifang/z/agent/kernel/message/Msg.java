package com.zifang.z.agent.kernel.message;

import com.zifang.z.agent.kernel.types.MessageRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 消息抽象. kernel 内部所有消息传递都用 Msg.
 *
 * <p>role: USER / ASSISTANT / SYSTEM / TOOL / FUNCTION.
 * <p>name: 多方会话角色名 (sub-agent / 多用户场景), 单方场景可为 null.
 * <p>content: 文本内容 (multimodal 用 metadata.urls / metadata.parts 表达).
 * <p>toolCallId: TOOL 角色专用, 关联 assistant 消息中的 toolCall.id.
 * <p>toolCalls: ASSISTANT 角色专用, LLM 发出的工具调用列表.
 * <p>metadata: 透传任意额外字段 (image_url / temperature / refusal 等).
 */
public final class Msg {

    private final MessageRole role;
    private final String name;
    private final String content;
    private final MessageType type;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;
    private final Map<String, Object> metadata;

    public Msg(MessageRole role, String content) {
        this(role, null, content, MessageType.TEXT, null, Collections.emptyList(), Collections.emptyMap());
    }

    public Msg(MessageRole role, String name, String content, MessageType type,
               String toolCallId, List<ToolCall> toolCalls, Map<String, Object> metadata) {
        this.role = role;
        this.name = name;
        this.content = content;
        this.type = type == null ? MessageType.TEXT : type;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        this.metadata = metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
    }

    public static Msg user(String content) {
        return new Msg(MessageRole.USER, content);
    }

    public static Msg assistant(String content) {
        return new Msg(MessageRole.ASSISTANT, content);
    }

    public static Msg system(String content) {
        return new Msg(MessageRole.SYSTEM, content);
    }

    public static Msg toolResult(String toolCallId, String content) {
        return new Msg(MessageRole.TOOL, null, content, MessageType.TOOL_RESULT, toolCallId,
                Collections.emptyList(), Collections.emptyMap());
    }

    public MessageRole getRole() {
        return role;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }

    public MessageType getType() {
        return type;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}