package com.zifang.z.agent.kernel.app.agent;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.agent.kernel.agent.Agent;
import com.zifang.z.agent.kernel.agent.AgentRequest;
import com.zifang.z.agent.kernel.agent.AgentResponse;
import com.zifang.z.agent.kernel.agent.IterationBudget;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * kernel 的第一个真 {@link Agent} 实现: 由 LLM 驱动, 带工具调用循环.
 *
 * <p>放在 kernel-app 而不是 kernel-agent, 是为了让 kernel-agent 保持纯 SPI —— 那边不引
 * kernel-llm, 于是 SPI 层不会因为"要支持 LLM"而被迫拖上 HTTP 栈.
 *
 * <p>循环形状（受 {@link IterationBudget} 约束, 不会无限调模型）:
 * <pre>
 *   msgs = [system?] + request.history + [request.input]
 *   while budget.canCall():
 *       resp = provider.chat(model, msgs, tools)
 *       没有 toolCalls → 收工, 这条 assistant 文本就是 output
 *       有 toolCalls   → 回灌 assistant 消息 + 每个调用的 toolResult, 继续下一轮
 * </pre>
 *
 * <p>工具名对不上时**回一条错误 toolResult** 而不是抛异常: 模型有机会改口重选，
 * 抛出去则整轮群聊直接失败.
 */
public class LlmAgent implements Agent {

    /**
     * 默认 token 上限. 注意别传 0: {@link IterationBudget} 判的是 {@code tokensUsed < maxTokens},
     * 传 0 等于"一开局就超额", 于是只剩一次 grace 调用, 工具循环跑不起来.
     */
    public static final long DEFAULT_MAX_TOKENS = 128000L;

    private final String name;
    private final LlmProvider provider;
    private final String model;
    private final String systemPrompt;
    private final List<Tool> tools;
    private final int maxSteps;
    private final long maxTokens;

    public LlmAgent(String name, LlmProvider provider, String model, String systemPrompt,
                    List<Tool> tools, int maxSteps) {
        this(name, provider, model, systemPrompt, tools, maxSteps, DEFAULT_MAX_TOKENS);
    }

    public LlmAgent(String name, LlmProvider provider, String model, String systemPrompt,
                    List<Tool> tools, int maxSteps, long maxTokens) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("agent name must not be empty");
        }
        if (provider == null) {
            throw new IllegalArgumentException("llm provider must not be null");
        }
        if (model == null || model.isEmpty()) {
            throw new IllegalArgumentException("model must not be empty");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive (0 means budget already exhausted)");
        }
        this.name = name;
        this.provider = provider;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.tools = tools == null ? Collections.<Tool>emptyList()
                : Collections.unmodifiableList(new ArrayList<Tool>(tools));
        this.maxSteps = maxSteps <= 0 ? 4 : maxSteps;
        this.maxTokens = maxTokens;
    }

    public static LlmAgent of(String name, LlmProvider provider, String model) {
        return new LlmAgent(name, provider, model, null, null, 4);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public AgentResponse run(AgentRequest request) {
        return drive(request, null);
    }

    /**
     * 步骤级回调: 每个工具调用一轮推一个 Step, 收尾推最终响应.
     * 注意 {@link AgentResponse.Step} 没有 token 级增量字段, 所以这里"流"的是步骤而不是
     * 逐 token —— 真要逐 token 得先给 Step 加一个 delta 通道, 别在这里假装有.
     */
    @Override
    public void streamRun(AgentRequest request, Consumer<AgentResponse.Step> onStep,
                          Consumer<AgentResponse> onComplete, Consumer<Throwable> onError) {
        try {
            onComplete.accept(drive(request, onStep));
        } catch (RuntimeException e) {
            if (onError != null) {
                onError.accept(e);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void reset() {
        // 无会话状态: 每次 run 的上下文都在方法局部, 天然干净
    }

    private AgentResponse drive(AgentRequest request, Consumer<AgentResponse.Step> onStep) {
        IterationBudget budget = new IterationBudget(maxSteps, maxTokens);
        List<Msg> msgs = seed(request);
        List<AgentResponse.Step> steps = new ArrayList<AgentResponse.Step>();
        long prompt = 0;
        long completion = 0;
        String finishReason = "max_steps";
        Msg output = null;

        while (budget.canCall()) {
            budget.onCall();
            ChatCompletionsResponse resp = provider.chat(
                    new ChatCompletionsRequest(model, msgs, tools, null, null, null, false, null));
            budget.recordTokens(resp.getUsage().getPromptTokens(), resp.getUsage().getCompletionTokens());
            prompt += resp.getUsage().getPromptTokens();
            completion += resp.getUsage().getCompletionTokens();

            if (resp.getChoices().isEmpty()) {
                output = Msg.assistant("");
                finishReason = "empty_response";
                break;
            }

            ChatCompletionsResponse.Choice choice = resp.getChoices().get(0);
            String content = choice.getContent() == null ? "" : choice.getContent();

            if (choice.getToolCalls().isEmpty()) {
                output = new Msg(MessageRole.ASSISTANT, null, content, MessageType.TEXT,
                        null, null, Collections.<String, Object>emptyMap());
                finishReason = "completed";
                break;
            }

            List<ToolCall> calls = choice.getToolCalls();
            msgs.add(new Msg(MessageRole.ASSISTANT, null, content, MessageType.TOOL_CALL,
                    null, calls, Collections.<String, Object>emptyMap()));

            StringBuilder observation = new StringBuilder();
            for (ToolCall call : calls) {
                String text = invoke(call);
                observation.append(text);
                // 必须回灌进消息列表, 下一轮模型才看得见工具输出;
                // 只拼给人看的 observation 字符串是不够的, 那样模型永远不知道工具说了什么。
                msgs.add(Msg.toolResult(call.getId(), text));
            }
            String observed = observation.toString();
            AgentResponse.Step step = new AgentResponse.Step(steps.size(), "tool", null,
                    Msg.assistant(joinCalls(calls)), Msg.user(observed), 0);
            steps.add(step);
            if (onStep != null) {
                onStep.accept(step);
            }
        }

        if (output == null) {
            output = Msg.assistant("");
        }
        return new AgentResponse(output, steps, new TokenUsage(prompt, completion, prompt + completion),
                finishReason, null, null);
    }

    private List<Msg> seed(AgentRequest request) {
        List<Msg> msgs = new ArrayList<Msg>();
        String system = request.getSystemPrompt() != null ? request.getSystemPrompt() : systemPrompt;
        if (system != null && !system.isEmpty()) {
            msgs.add(Msg.system(system));
        }
        msgs.addAll(request.getHistory());
        if (request.getInput() != null) {
            msgs.add(request.getInput());
        }
        return msgs;
    }

    /** 执行一次工具调用, 返回回灌给模型的那段文本（成功或失败都回, 失败带前缀）. */
    private String invoke(ToolCall call) {
        Tool tool = findTool(call.getName());
        if (tool == null) {
            return call.getName() + " -> [error] 未知工具: " + call.getName();
        }
        ToolResult result;
        try {
            result = tool.execute(parseArguments(call.getArgumentsJson()));
        } catch (RuntimeException e) {
            return call.getName() + " -> [error] " + e.getMessage();
        }
        String payload = result.getContent() == null ? "" : result.getContent();
        return call.getName() + " -> " + (result.isError() ? "[error] " : "") + payload;
    }

    private Tool findTool(String toolName) {
        for (Tool tool : tools) {
            if (tool.getName().equals(toolName)) {
                return tool;
            }
        }
        return null;
    }

    private static Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> parsed = JsonUtil.parseToMap(argumentsJson);
        return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
    }

    private static String joinCalls(List<ToolCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (ToolCall call : calls) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(call.getName());
        }
        return sb.toString();
    }

    /** 本 agent 可用工具（给上层展示/组装用）. */
    public List<Tool> getTools() {
        return tools;
    }

    public String getModel() {
        return model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }
}
