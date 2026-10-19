package com.zifang.z.agent.kernel.agent.group;

import com.zifang.z.agent.kernel.agent.Agent;
import com.zifang.z.agent.kernel.agent.AgentRequest;
import com.zifang.z.agent.kernel.agent.AgentResponse;
import com.zifang.z.agent.kernel.message.Msg;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * pin 住群聊能"跑下去并收敛"这件事: 一条真人消息进, 多个 agent 依次答, 然后停.
 */
public class GroupChatRunnerTest {

    /** 只回一句话的假 agent, 顺带把收到的 context 记下来供断言. */
    private static class EchoAgent implements Agent {
        private final String name;
        private final List<AgentRequest> seen = new ArrayList<AgentRequest>();
        private final AtomicInteger calls = new AtomicInteger();

        EchoAgent(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public AgentResponse run(AgentRequest request) {
            seen.add(request);
            calls.incrementAndGet();
            return new AgentResponse(Msg.assistant(name + " 收到"), null, null, "completed", null, null);
        }

        @Override
        public void streamRun(AgentRequest request, Consumer<AgentResponse.Step> onStep,
                              Consumer<AgentResponse> onComplete, Consumer<Throwable> onError) {
            onComplete.accept(run(request));
        }

        @Override
        public void reset() {
        }
    }

    private List<Participant> roster() {
        return Arrays.asList(
                Participant.human("u1", "张三"),
                Participant.agent("a1", "小测", "测试工程师"),
                Participant.agent("a2", "小码", "开发工程师"));
    }

    private Map<String, Agent> agents(Agent... as) {
        Map<String, Agent> m = new LinkedHashMap<String, Agent>();
        for (Agent a : as) {
            m.put(a.getName(), a);
        }
        return m;
    }

    @Test
    public void broadcast_letsEveryAgentAnswerOnceThenConverges() {
        EchoAgent a1 = new EchoAgent("a1");
        EchoAgent a2 = new EchoAgent("a2");
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(a1, a2), new BroadcastPolicy());

        GroupChatResult result = runner.run(GroupMessage.of(0, "u1", "张三", "线上炸了，谁看看？"));

        assertTrue(result.isConverged());
        assertEquals("converged", result.getFinishedReason());
        assertEquals(Integer.valueOf(1), result.getSpeakCount().get("a1"));
        assertEquals(Integer.valueOf(1), result.getSpeakCount().get("a2"));
        assertEquals(1, a1.calls.get());
        assertEquals(1, a2.calls.get());

        List<GroupMessage> t = result.getTranscript();
        assertEquals(3, t.size());
        assertEquals("u1", t.get(0).getSenderId());
        assertEquals("a1", t.get(1).getSenderId());
        assertEquals("a2", t.get(2).getSenderId());
        // seq 单调递增, 供上下文截断与派单引用
        assertTrue(t.get(0).getSeq() < t.get(1).getSeq());
        assertTrue(t.get(1).getSeq() < t.get(2).getSeq());
    }

    @Test
    public void agentSeesWhoSpokeNotJustText() {
        EchoAgent a1 = new EchoAgent("a1");
        EchoAgent a2 = new EchoAgent("a2");
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(a1, a2), new BroadcastPolicy());
        runner.run(GroupMessage.of(0, "u1", "张三", "线上炸了"));

        AgentRequest secondAgentView = a2.seen.get(0);
        // 最新那条作 input, 更早的进 history: a2 看到 a1 的话作 input, 真人的话作 history
        assertEquals(1, secondAgentView.getHistory().size());
        String rendered = secondAgentView.getHistory().get(0).getContent();
        assertTrue("history 应带发言人前缀: " + rendered, rendered.contains("张三"));
        assertEquals("u1", secondAgentView.getHistory().get(0).getName());
        assertNotNull(secondAgentView.getInput());
        assertTrue(secondAgentView.getInput().getContent().contains("[小测]"));
        assertEquals("a1", secondAgentView.getInput().getName());
    }

    @Test
    public void mentionOnly_picksNamedAgentAndStopsAfterOneRound() {
        EchoAgent a1 = new EchoAgent("a1");
        EchoAgent a2 = new EchoAgent("a2");
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(a1, a2), new MentionPolicy());

        GroupChatResult result = runner.run(
                GroupMessage.mentioned(0, "u1", "张三", "@小码 帮忙看下", Arrays.asList("a2")));

        assertTrue(result.isConverged());
        assertEquals(0, a1.calls.get());
        assertEquals(1, a2.calls.get());
        assertNull(result.getSpeakCount().get("a1"));
        assertEquals(2, result.getTranscript().size());
    }

    @Test
    public void mentionPolicy_parsesAtPrefixFromContent() {
        EchoAgent a1 = new EchoAgent("a1");
        EchoAgent a2 = new EchoAgent("a2");
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(a1, a2), new MentionPolicy());
        runner.run(GroupMessage.of(0, "u1", "张三", "@a1 @a2 都看下"));
        assertEquals(1, a1.calls.get());
        assertEquals(1, a2.calls.get());
    }

    @Test
    public void unknownResponder_isSkippedNotSwallowed() {
        EchoAgent a1 = new EchoAgent("a1");
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(a1),
                new MentionPolicy());
        GroupChatResult result = runner.run(
                GroupMessage.mentioned(0, "u1", "张三", "@ghost", Arrays.asList("ghost")));
        assertEquals(Arrays.asList("ghost"), result.getSkippedResponders());
        assertEquals(1, result.getTranscript().size());
    }

    @Test
    public void rounds_areCapped() {
        /** 永远点同一批人 —— 已经说过话的不再点, 所以其实一轮就收敛. */
        ResponsePolicy forever = new ResponsePolicy() {
            @Override
            public List<String> selectResponders(GroupChatContext ctx) {
                return Arrays.asList("a1");
            }
        };
        EchoAgent a1 = new EchoAgent("a1");
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(a1), forever, 5, 20);
        GroupChatResult result = runner.run(GroupMessage.of(0, "u1", "张三", "在吗"));
        assertTrue(result.isConverged());
        assertEquals(1, a1.calls.get());
    }

    @Test
    public void seqContinuesAcrossRuns() {
        EchoAgent a1 = new EchoAgent("a1");
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(a1), new BroadcastPolicy());
        runner.run(GroupMessage.of(0, "u1", "张三", "第一句"));
        GroupChatResult second = runner.run(GroupMessage.of(0, "u1", "张三", "第二句"));
        List<GroupMessage> t = second.getTranscript();
        assertEquals(4, t.size());
        assertTrue(t.get(2).getSeq() > t.get(1).getSeq());
        assertTrue(t.get(3).getSeq() > t.get(2).getSeq());
        // 同一个 agent 在第二次 run 里又获得一次发言机会（收敛是按 run 计的）
        assertEquals(Integer.valueOf(1), second.getSpeakCount().get("a1"));
    }

    @Test
    public void agentFailure_stopsWithFailedReason() {
        Agent boom = new Agent() {
            @Override
            public String getName() {
                return "a1";
            }

            @Override
            public AgentResponse run(AgentRequest request) {
                throw new IllegalStateException("model down");
            }

            @Override
            public void streamRun(AgentRequest request, Consumer<AgentResponse.Step> onStep,
                                  Consumer<AgentResponse> onComplete, Consumer<Throwable> onError) {
            }

            @Override
            public void reset() {
            }
        };
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(boom), new BroadcastPolicy());
        GroupChatResult result = runner.run(GroupMessage.of(0, "u1", "张三", "跑一下"));
        assertEquals("failed", result.getFinishedReason());
        assertNotNull(result.getError());
        assertEquals("model down", result.getError().getMessage());
    }

    @Test
    public void streamCallbacksFire() {
        EchoAgent a1 = new EchoAgent("a1");
        EchoAgent a2 = new EchoAgent("a2");
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(a1, a2), new BroadcastPolicy());

        final List<GroupMessage> streamed = new ArrayList<GroupMessage>();
        GroupChatResult result = runner.run(GroupMessage.of(0, "u1", "张三", "在吗"),
                null, new Consumer<GroupMessage>() {
                    @Override
                    public void accept(GroupMessage m) {
                        streamed.add(m);
                    }
                });

        assertEquals(2, streamed.size());
        assertEquals(result.getTranscript().size() - 1, streamed.size());
        assertFalse(streamed.get(0).getSenderId().equals("u1"));
    }

    @Test
    public void emptyRosterRejected() {
        try {
            new GroupChatRunner(new ArrayList<Participant>(), agents(new EchoAgent("a1")), new BroadcastPolicy());
            fail("empty roster should be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("roster"));
        }
    }

    @Test
    public void historyWindow_trimsWhatAgentSees() {
        EchoAgent a1 = new EchoAgent("a1");
        EchoAgent a2 = new EchoAgent("a2");
        GroupChatRunner runner = new GroupChatRunner(roster(), agents(a1, a2), new BroadcastPolicy(), 3, 1);
        runner.run(GroupMessage.of(0, "u1", "张三", "第一句"));
        runner.run(GroupMessage.of(0, "u1", "张三", "第二句"));

        AgentRequest view = a2.seen.get(a2.seen.size() - 1);
        // window=1: 只有本轮那条作为 input, history 空
        assertTrue(view.getHistory().isEmpty());
        assertNotNull(view.getInput());
    }
}
