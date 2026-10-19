package com.zifang.z.agent.kernel.app.agent;

import com.zifang.z.agent.kernel.agent.AgentRequest;
import com.zifang.z.agent.kernel.agent.AgentResponse;
import com.zifang.z.agent.kernel.agent.group.BroadcastPolicy;
import com.zifang.z.agent.kernel.agent.group.GroupChatRunner;
import com.zifang.z.agent.kernel.agent.group.GroupMessage;
import com.zifang.z.agent.kernel.agent.group.Participant;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.TokenUsage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * pin 住 LLM 驱动的 Agent: 工具调用循环、预算上限、群聊端到端真跑一遍.
 */
public class LlmAgentTest {

    /** 脚本化 provider: 按预设脚本一轮一个响应, 并记录收到的消息. */
    private static class ScriptedProvider implements LlmProvider {
        private final List<ChatCompletionsResponse> script = new ArrayList<ChatCompletionsResponse>();
        private final List<ChatCompletionsRequest> seen = new ArrayList<ChatCompletionsRequest>();

        ScriptedProvider respond(String content) {
            script.add(text(content));
            return this;
        }

        ScriptedProvider toolCall(String callId, String toolName, String argsJson) {
            script.add(new ChatCompletionsResponse("id", "m",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(0, null,
                            Collections.singletonList(new ToolCall(callId, toolName, argsJson)), "tool_calls")),
                    new TokenUsage(10, 5, 15), "tool_calls", Collections.emptyMap()));
            return this;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public List<Model> listModels() {
            return Collections.emptyList();
        }

        @Override
        public boolean supportsModel(String modelId) {
            return true;
        }

        @Override
        public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            seen.add(request);
            if (script.isEmpty()) {
                throw new IllegalStateException("脚本用尽，第 " + seen.size() + " 次调用没有预设响应");
            }
            return script.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }

        private static ChatCompletionsResponse text(String content) {
            return new ChatCompletionsResponse("id", "m",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(
                            0, content, Collections.emptyList(), "stop")),
                    new TokenUsage(20, 10, 30), "stop", Collections.emptyMap());
        }
    }

    private static Tool echoTool(final String toolName) {
        return new Tool() {
            @Override
            public String getName() {
                return toolName;
            }

            @Override
            public String getDescription() {
                return "回显参数";
            }

            @Override
            public Map<String, Object> getSchema() {
                return Collections.emptyMap();
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return ToolResult.success("c1", toolName, "echo:" + arguments);
            }
        };
    }

    private AgentRequest request(String content) {
        return new AgentRequest(Msg.user(content), Collections.<Msg>emptyList(),
                Collections.<Tool>emptyList(), null, Collections.<String, Object>emptyMap());
    }

    @Test
    public void plainAnswer_completesInOneCall() {
        ScriptedProvider p = new ScriptedProvider().respond("好了");
        AgentResponse resp = LlmAgent.of("a1", p, "m1").run(request("在吗"));

        assertEquals("completed", resp.getFinishedReason());
        assertEquals("好了", resp.getOutput().getContent());
        assertEquals(1, p.seen.size());
        assertTrue(resp.getSteps().isEmpty());
        assertEquals(30L, resp.getUsage().getTotalTokens());
    }

    @Test
    public void toolCall_isExecutedAndAnsweredOnSecondRound() {
        ScriptedProvider p = new ScriptedProvider()
                .toolCall("call-1", "echo", "{\"q\":\"炸了\"}")
                .respond("我查完了：是连接池打满");
        LlmAgent agent = new LlmAgent("a1", p, "m1", "你是工程师",
                Collections.singletonList(echoTool("echo")), 4);

        AgentResponse resp = agent.run(request("线上炸了"));

        assertEquals("completed", resp.getFinishedReason());
        assertEquals("我查完了：是连接池打满", resp.getOutput().getContent());
        assertEquals(2, p.seen.size());
        assertEquals(1, resp.getSteps().size());
        assertEquals("tool", resp.getSteps().get(0).getKind());

        // 第二轮请求里必须带着 assistant 的 toolCalls 与 tool 回执:
        // [system, user, assistant(toolCalls), toolResult]
        List<Msg> second = p.seen.get(1).getMessages();
        assertEquals(4, second.size());
        assertEquals(1, second.get(2).getToolCalls().size());
        assertEquals("call-1", second.get(2).getToolCalls().get(0).getId());
        assertEquals("call-1", second.get(3).getToolCallId());
        assertTrue("工具结果要回灌: " + second.get(3).getContent(),
                second.get(3).getContent().contains("echo:{q=炸了}"));
    }

    @Test
    public void systemPrompt_isPrependedAndOverridable() {
        ScriptedProvider p = new ScriptedProvider().respond("ok");
        LlmAgent agent = new LlmAgent("a1", p, "m1", "你是测试工程师", null, 2);
        agent.run(request("hi"));
        assertEquals("你是测试工程师", p.seen.get(0).getMessages().get(0).getContent());

        ScriptedProvider p2 = new ScriptedProvider().respond("ok");
        new LlmAgent("a1", p2, "m1", "你是测试工程师", null, 2)
                .run(new AgentRequest(Msg.user("hi"), Collections.<Msg>emptyList(),
                Collections.<Tool>emptyList(), "临时覆盖", Collections.<String, Object>emptyMap()));
        assertEquals("临时覆盖", p2.seen.get(0).getMessages().get(0).getContent());
    }

    @Test
    public void budgetExhausted_grantsOneGraceCallToWrapUp() {
        // maxSteps=2 但模型连续要工具: 预算用完后 IterationBudget 仍给一次 grace 收尾调用,
        // 这一次正好用来把话说完, 于是 finishedReason 是 completed 而不是 max_steps。
        ScriptedProvider p = new ScriptedProvider()
                .toolCall("c1", "echo", "{}")
                .toolCall("c2", "echo", "{}")
                .respond("收尾：已处理");
        LlmAgent agent = new LlmAgent("a1", p, "m1", null,
                Collections.singletonList(echoTool("echo")), 2);

        AgentResponse resp = agent.run(request("go"));
        assertEquals("completed", resp.getFinishedReason());
        assertEquals("收尾：已处理", resp.getOutput().getContent());
        assertEquals(3, p.seen.size());
        assertEquals(2, resp.getSteps().size());
    }

    @Test
    public void budgetExhaustedAndStillCallingTools_reportsMaxSteps() {
        ScriptedProvider p = new ScriptedProvider()
                .toolCall("c1", "echo", "{}")
                .toolCall("c2", "echo", "{}")
                .toolCall("c3", "echo", "{}")
                .respond("不该被问到");
        LlmAgent agent = new LlmAgent("a1", p, "m1", null,
                Collections.singletonList(echoTool("echo")), 2);

        AgentResponse resp = agent.run(request("go"));
        assertEquals("max_steps", resp.getFinishedReason());
        assertEquals(3, p.seen.size());
        assertEquals(3, resp.getSteps().size());
        assertEquals("", resp.getOutput().getContent());
    }

    @Test
    public void maxTokensZeroRejectedBecauseBudgetStartsExhausted() {
        try {
            new LlmAgent("a1", new ScriptedProvider(), "m1", null, null, 2, 0);
            fail("maxTokens=0 应被拒");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("maxTokens"));
        }
    }

    @Test
    public void unknownTool_getsErrorFeedbackInsteadOfCrash() {
        ScriptedProvider p = new ScriptedProvider()
                .toolCall("c1", "不存在", "{}")
                .respond("那我换个说法");
        LlmAgent agent = new LlmAgent("a1", p, "m1", null,
                Collections.singletonList(echoTool("echo")), 4);

        AgentResponse resp = agent.run(request("go"));
        assertEquals("completed", resp.getFinishedReason());
        assertTrue(p.seen.get(1).getMessages().get(2).getContent().contains("未知工具"));
    }

    @Test
    public void groupChat_runsEndToEndOnRealAgents() {
        ScriptedProvider p1 = new ScriptedProvider().respond("我看日志，是超时");
        ScriptedProvider p2 = new ScriptedProvider().respond("我去改超时配置");
        Map<String, com.zifang.z.agent.kernel.agent.Agent> agents = new LinkedHashMap<String, com.zifang.z.agent.kernel.agent.Agent>();
        agents.put("a1", new LlmAgent("a1", p1, "m1", "你是 SRE", null, 4));
        agents.put("a2", new LlmAgent("a2", p2, "m1", "你是开发", null, 4));

        GroupChatRunner runner = new GroupChatRunner(Arrays.asList(
                Participant.human("u1", "张三"),
                Participant.agent("a1", "小 SRE", "SRE"),
                Participant.agent("a2", "小码", "开发")),
                agents, new BroadcastPolicy());

        com.zifang.z.agent.kernel.agent.group.GroupChatResult result =
                runner.run(GroupMessage.of(0, "u1", "张三", "线上大面积超时，咋办？"));

        assertTrue(result.isConverged());
        assertEquals(3, result.getTranscript().size());
        assertEquals("我看日志，是超时", result.getTranscript().get(1).getContent());
        assertEquals("我去改超时配置", result.getTranscript().get(2).getContent());
        // 每个 agent 的 system prompt 都进了模型, 且看得见群里的其他人说话
        assertEquals("你是 SRE", p1.seen.get(0).getMessages().get(0).getContent());
        StringBuilder a2View = new StringBuilder();
        for (Msg m : p2.seen.get(0).getMessages()) {
            a2View.append(m.getContent()).append('\n');
        }
        assertTrue("a2 应看得见小 SRE 的发言: " + a2View, a2View.toString().contains("小 SRE"));
    }

    @Test
    public void streamRun_pushesToolStepThenCompletes() {
        ScriptedProvider p = new ScriptedProvider()
                .toolCall("c1", "echo", "{}")
                .respond("好了");
        LlmAgent agent = new LlmAgent("a1", p, "m1", null,
                Collections.singletonList(echoTool("echo")), 4);

        final List<AgentResponse.Step> steps = new ArrayList<AgentResponse.Step>();
        final List<AgentResponse> done = new ArrayList<AgentResponse>();
        agent.streamRun(request("go"), steps::add, done::add, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable t) {
                fail("不该失败: " + t);
            }
        });

        assertEquals(1, steps.size());
        assertEquals(1, done.size());
        assertEquals("好了", done.get(0).getOutput().getContent());
        assertNotNull(done.get(0).getUsage());
    }

    @Test
    public void emptyChoices_becomesEmptyResponseNotCrash() {
        ScriptedProvider p = new ScriptedProvider() {
            @Override
            public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
                return new ChatCompletionsResponse("id", "m", null, null, null, null);
            }
        };
        AgentResponse resp = LlmAgent.of("a1", p, "m1").run(request("在吗"));
        assertEquals("empty_response", resp.getFinishedReason());
        assertEquals("", resp.getOutput().getContent());
    }

    @Test
    public void constructorRejectsMissingEssentials() {
        ScriptedProvider p = new ScriptedProvider();
        try {
            new LlmAgent("", p, "m1", null, null, 1);
            fail("空名字应被拒");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("name"));
        }
        try {
            LlmAgent.of("a1", null, "m1");
            fail("null provider 应被拒");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("provider"));
        }
    }
}
