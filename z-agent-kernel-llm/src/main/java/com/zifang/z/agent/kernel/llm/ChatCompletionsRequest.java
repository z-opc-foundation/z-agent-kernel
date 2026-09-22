package com.zifang.z.agent.kernel.llm;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.Tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Chat Completions 请求 DTO. 各 provider 通过这个统一格式接收请求, 内部映射到自家协议.
 *
 * <p>model: 模型 id (例如 "gpt-4o" / "claude-sonnet-4").
 * <p>messages: kernel 内部 Msg 列表, 由 provider 自行映射.
 * <p>tools: 可选工具列表 (OpenAI 风格 JSON Schema).
 * <p>temperature/topP/maxTokens: 通用采样参数.
 */
public final class ChatCompletionsRequest {

    private final String model;
    private final List<Msg> messages;
    private final List<Tool> tools;
    private final Double temperature;
    private final Double topP;
    private final Integer maxTokens;
    private final boolean stream;
    private final Map<String, Object> providerParams;

    public ChatCompletionsRequest(String model, List<Msg> messages) {
        this(model, messages, null, null, null, null, false, null);
    }

    public ChatCompletionsRequest(String model, List<Msg> messages, List<Tool> tools,
                                  Double temperature, Double topP, Integer maxTokens,
                                  boolean stream, Map<String, Object> providerParams) {
        this.model = model;
        this.messages = messages == null ? Collections.emptyList() : Collections.unmodifiableList(messages);
        this.tools = tools == null ? Collections.emptyList() : Collections.unmodifiableList(tools);
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.stream = stream;
        this.providerParams = providerParams == null ? Collections.emptyMap() : Collections.unmodifiableMap(providerParams);
    }

    public String getModel() {
        return model;
    }

    public List<Msg> getMessages() {
        return messages;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public boolean isStream() {
        return stream;
    }

    public Map<String, Object> getProviderParams() {
        return providerParams;
    }

    public List<ToolCall> flattenToolCalls() {
        java.util.List<ToolCall> out = new java.util.ArrayList<>();
        for (Msg m : messages) {
            out.addAll(m.getToolCalls());
        }
        return out;
    }
}