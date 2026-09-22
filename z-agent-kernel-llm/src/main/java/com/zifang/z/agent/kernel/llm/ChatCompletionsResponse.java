package com.zifang.z.agent.kernel.llm;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Chat Completions 响应 DTO. provider 返回的统一格式, 上层 agent 用它组装最终输出.
 *
 * <p>id: provider 分配 (OpenAI chatcmpl-*, Anthropic msg_*).
 * <p>model: 实际命中的模型 (可能跟请求的 model 不一样, 比如请求 gpt-4 命中 gpt-4-0613).
 * <p>choices: 通常 1 个, 流式场景下也是 1 个最终聚合.
 * <p>usage: token 统计, 部分 provider 流式场景下累计到最后一个 chunk.
 */
public final class ChatCompletionsResponse {

    private final String id;
    private final String model;
    private final List<Choice> choices;
    private final TokenUsage usage;
    private final String finishReason;
    private final Map<String, Object> providerMetadata;

    public ChatCompletionsResponse(String id, String model, List<Choice> choices,
                                   TokenUsage usage, String finishReason, Map<String, Object> providerMetadata) {
        this.id = id;
        this.model = model;
        this.choices = choices == null ? Collections.emptyList() : Collections.unmodifiableList(choices);
        this.usage = usage == null ? TokenUsage.empty() : usage;
        this.finishReason = finishReason;
        this.providerMetadata = providerMetadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(providerMetadata);
    }

    public String getId() {
        return id;
    }

    public String getModel() {
        return model;
    }

    public List<Choice> getChoices() {
        return choices;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public Map<String, Object> getProviderMetadata() {
        return providerMetadata;
    }

    public Msg toAssistantMsg() {
        if (choices.isEmpty()) {
            return new Msg(MessageRole.ASSISTANT, "");
        }
        Choice c = choices.get(0);
        if (c.getToolCalls().isEmpty()) {
            return new Msg(MessageRole.ASSISTANT, c.getContent());
        }
        return new Msg(MessageRole.ASSISTANT, null, c.getContent(), com.zifang.z.agent.kernel.message.MessageType.TOOL_CALL,
                null, c.getToolCalls(), Collections.emptyMap());
    }

    public static final class Choice {

        private final int index;
        private final String content;
        private final List<com.zifang.z.agent.kernel.message.ToolCall> toolCalls;
        private final String finishReason;

        public Choice(int index, String content, List<com.zifang.z.agent.kernel.message.ToolCall> toolCalls, String finishReason) {
            this.index = index;
            this.content = content;
            this.toolCalls = toolCalls == null ? Collections.emptyList() : Collections.unmodifiableList(toolCalls);
            this.finishReason = finishReason;
        }

        public int getIndex() {
            return index;
        }

        public String getContent() {
            return content;
        }

        public List<com.zifang.z.agent.kernel.message.ToolCall> getToolCalls() {
            return toolCalls;
        }

        public String getFinishReason() {
            return finishReason;
        }
    }
}