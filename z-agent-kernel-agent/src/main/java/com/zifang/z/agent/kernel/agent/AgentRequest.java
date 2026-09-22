package com.zifang.z.agent.kernel.agent;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.tool.Tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 调用请求 DTO.
 *
 * <p>input: 用户输入 (单条 Msg, 通常 USER role).
 * <p>history: 多轮对话历史 (USER / ASSISTANT / TOOL 混合).
 * <p>tools: 可用工具列表 (agent 级别约束).
 * <p>systemPrompt: 系统提示覆盖.
 * <p>config: agent 级别运行配置 (maxSteps / temperature / model 等).
 */
public final class AgentRequest {

    private final Msg input;
    private final List<Msg> history;
    private final List<Tool> tools;
    private final String systemPrompt;
    private final Map<String, Object> config;

    public AgentRequest(Msg input, List<Msg> history, List<Tool> tools, String systemPrompt, Map<String, Object> config) {
        this.input = input;
        this.history = history == null ? Collections.emptyList() : Collections.unmodifiableList(history);
        this.tools = tools == null ? Collections.emptyList() : Collections.unmodifiableList(tools);
        this.systemPrompt = systemPrompt;
        this.config = config == null ? Collections.emptyMap() : Collections.unmodifiableMap(config);
    }

    public Msg getInput() {
        return input;
    }

    public List<Msg> getHistory() {
        return history;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Map<String, Object> getConfig() {
        return config;
    }
}