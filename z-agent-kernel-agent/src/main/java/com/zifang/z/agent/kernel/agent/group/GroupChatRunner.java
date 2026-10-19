package com.zifang.z.agent.kernel.agent.group;

import com.zifang.z.agent.kernel.agent.Agent;
import com.zifang.z.agent.kernel.agent.AgentRequest;
import com.zifang.z.agent.kernel.agent.AgentResponse;
import com.zifang.z.agent.kernel.message.Msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 群聊编排器 —— 让"一条消息进、一屋子 agent 答、然后收敛"这件事真的跑起来.
 *
 * <p>循环形状:
 * <pre>
 *   transcript += 触发消息
 *   round = 0
 *   while round &lt; maxRounds:
 *       responders = policy.selectResponders(ctx)   // 没人接 → 收敛退出
 *       responders -= 本次 run 已发言过的人        // 每人至多一口
 *       responders -= 名册里不是 agent / agent 表查不到的人
 *       if responders.isEmpty(): converged
 *       for each responder: agent.run(...) → transcript += 发言
 *       round++
 * </pre>
 *
 * <p>seq 计数器留在 runner 实例上, 所以同一个 runner 连着跑多次 run, 消息序号连续 ——
 * agent 的上下文截断与上层派单都依赖这个顺序.
 *
 * <p>本类不依赖 kernel-llm: 编排只跟 {@link Agent} 打交道. 真正调模型的是
 * {@code Agent} 实现（kernel 的 {@code LlmAgent} 在 kernel-app 里）, 上层想换成
 * z-team 现存的那套执行器也行 —— 那是同一个 SPI 的另一个实现.
 */
public final class GroupChatRunner {

    private final Map<String, Participant> roster;
    private final Map<String, Agent> agents;
    private final ResponsePolicy policy;
    private final GroupTranscript transcriptView;

    private final int maxRounds;
    private final int historyWindow;

    private final List<GroupMessage> transcript = new ArrayList<GroupMessage>();
    private long seq;

    public GroupChatRunner(List<Participant> roster, Map<String, Agent> agents,
                           ResponsePolicy policy, int maxRounds, int historyWindow) {
        if (roster == null || roster.isEmpty()) {
            throw new IllegalArgumentException("roster must not be empty");
        }
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("agents must not be empty");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.roster = new LinkedHashMap<String, Participant>();
        for (Participant p : roster) {
            this.roster.put(p.getId(), p);
        }
        this.agents = new LinkedHashMap<String, Agent>(agents);
        this.policy = policy;
        this.maxRounds = maxRounds <= 0 ? 1 : maxRounds;
        this.historyWindow = historyWindow;
        this.transcriptView = new GroupTranscript(this.roster);
    }

    public GroupChatRunner(List<Participant> roster, Map<String, Agent> agents, ResponsePolicy policy) {
        this(roster, agents, policy, 3, 20);
    }

    /** 同步跑完一轮群聊, 返回完整结果. */
    public GroupChatResult run(GroupMessage trigger) {
        return drive(trigger, null, null);
    }

    /**
     * 流式跑: 每个 agent 步骤完成回调 onStep, 每条群发言回调 onMessage, 收尾回调 onComplete.
     * 三条回调都可为 null.
     */
    public GroupChatResult run(GroupMessage trigger,
                               Consumer<AgentResponse.Step> onStep,
                               Consumer<GroupMessage> onMessage) {
        return drive(trigger, onStep, onMessage);
    }

    /** 当前群历史（按 seq 升序的快照）. */
    public List<GroupMessage> history() {
        return Collections.unmodifiableList(new ArrayList<GroupMessage>(transcript));
    }

    private GroupChatResult drive(GroupMessage trigger,
                                  Consumer<AgentResponse.Step> onStep,
                                  Consumer<GroupMessage> onMessage) {
        GroupMessage latest = append(trigger);

        List<String> alreadySpoken = new ArrayList<String>();
        List<String> skipped = new ArrayList<String>();
        Map<String, Integer> speakCount = new LinkedHashMap<String, Integer>();
        Throwable failure = null;
        int round = 0;
        String finishedReason = "converged";

        while (round < maxRounds) {
            GroupChatContext ctx = new GroupChatContext(roster, history(), latest, alreadySpoken, round);
            List<String> responders = new ArrayList<String>();
            for (String id : policy.selectResponders(ctx)) {
                if (responders.contains(id)) {
                    continue;
                }
                if (alreadySpoken.contains(id)) {
                    continue;
                }
                Participant p = roster.get(id);
                if (p == null || !p.isAgent() || !agents.containsKey(id)) {
                    skipped.add(id);
                    continue;
                }
                responders.add(id);
            }

            if (responders.isEmpty()) {
                finishedReason = "converged";
                break;
            }

            for (String id : responders) {
                AgentResponse response;
                try {
                    response = agents.get(id).run(requestFor(id));
                } catch (RuntimeException e) {
                    failure = e;
                    finishedReason = "failed";
                    break;
                }
                if (onStep != null && response != null) {
                    for (AgentResponse.Step step : response.getSteps()) {
                        onStep.accept(step);
                    }
                }
                String content = response == null || response.getOutput() == null
                        ? "" : response.getOutput().getContent();
                GroupMessage spoken = append(new GroupMessage(0, id,
                        roster.get(id).getDisplayName(), content, null));
                alreadySpoken.add(id);
                Integer n = speakCount.get(id);
                speakCount.put(id, n == null ? 1 : n + 1);
                if (onMessage != null) {
                    onMessage.accept(spoken);
                }
            }

            if ("failed".equals(finishedReason)) {
                break;
            }
            round++;
            if (round >= maxRounds) {
                finishedReason = "max_rounds";
            }
            // 下一轮的最新发言 = 刚被选中的最后一位 agent 的话, 于是"接茬"的对象随轮次推进
            latest = transcript.get(transcript.size() - 1);
        }

        return new GroupChatResult(history(), speakCount, round, finishedReason, skipped, failure);
    }

    private AgentRequest requestFor(String agentId) {
        List<GroupMessage> all = history();
        List<GroupMessage> windowed = historyWindow <= 0 || all.size() <= historyWindow
                ? all : all.subList(all.size() - historyWindow, all.size());
        List<Msg> msgs = transcriptView.toMessages(windowed, 0);
        Msg input = msgs.isEmpty() ? Msg.user("") : msgs.get(msgs.size() - 1);
        List<Msg> history = msgs.isEmpty()
                ? Collections.<Msg>emptyList()
                : new ArrayList<Msg>(msgs.subList(0, msgs.size() - 1));

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("groupId", "group");
        config.put("participantId", agentId);

        // tools 留空: 工具集是 Agent 实现自己的持有物（Agent SPI 不暴露它），
        // LlmAgent 会在实现内部用自己那份组请求。
        return new AgentRequest(input, history, Collections.<com.zifang.z.agent.kernel.tool.Tool>emptyList(),
                null, config);
    }

    /**
     * 入群并统一发号: seq 由 runner 独家分配, 调用方自带的 seq 一律作废 ——
     * 否则真人消息常带着 0 入群, 会把 agent 的发言序号顶乱, 上下文截断就取错段.
     */
    private GroupMessage append(GroupMessage message) {
        GroupMessage numbered = new GroupMessage(++seq, message.getSenderId(),
                message.getSenderName(), message.getContent(), message.getMentions());
        transcript.add(numbered);
        return numbered;
    }
}
