package com.zifang.z.agent.kernel.agent.group;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交给 {@link ResponsePolicy} 做"谁该开口"决策的只读视图.
 *
 * <p>策略看到三样东西: 名册、完整 transcript、本条最新发言; 外加 alreadySpoken —— 本次
 * {@link GroupChatRunner#run} 里已经说过话的 agent。收敛靠它: 策略连续返回空名单, 或者
 * 只返回已经说过话的人, 群就停下来, 不会两个 agent 无限对话下去。
 */
public final class GroupChatContext {

    private final Map<String, Participant> roster;
    private final List<GroupMessage> transcript;
    private final GroupMessage latest;
    private final List<String> alreadySpoken;
    private final int round;

    public GroupChatContext(Map<String, Participant> roster, List<GroupMessage> transcript,
                            GroupMessage latest, List<String> alreadySpoken, int round) {
        this.roster = Collections.unmodifiableMap(new LinkedHashMap<String, Participant>(roster));
        this.transcript = Collections.unmodifiableList(transcript);
        this.latest = latest;
        this.alreadySpoken = Collections.unmodifiableList(alreadySpoken);
        this.round = round;
    }

    public Map<String, Participant> getRoster() {
        return roster;
    }

    public List<GroupMessage> getTranscript() {
        return transcript;
    }

    /** 本轮要处理的那条发言; 第一轮就是触发这次群聊的真人消息. */
    public GroupMessage getLatest() {
        return latest;
    }

    /** 本次 run 里已经发言过的 agent id（按发言顺序）. */
    public List<String> getAlreadySpoken() {
        return alreadySpoken;
    }

    /** 从 0 开始计的轮次. */
    public int getRound() {
        return round;
    }

    public Participant participant(String id) {
        return roster.get(id);
    }

    /** 名册里所有 agent 成员, 保持名册顺序. */
    public List<Participant> agents() {
        java.util.List<Participant> out = new java.util.ArrayList<Participant>();
        for (Participant p : roster.values()) {
            if (p.isAgent()) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
