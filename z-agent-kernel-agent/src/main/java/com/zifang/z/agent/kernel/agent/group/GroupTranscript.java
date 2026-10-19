package com.zifang.z.agent.kernel.agent.group;

import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 群 transcript → 模型消息的渲染.
 *
 * <p>关键在 speaker 前缀: 群聊里"谁在说"是语义的一部分, 而 Chat Completions 只有
 * role 没有 speaker, 所以每条内容都带 {@code [发言人] 正文}, 并把发言人写进
 * {@link Msg#getName()}。少了前缀, 多 agent 会把自己的话当成别人的, 群聊就退化成独白.
 */
public final class GroupTranscript {

    private final Map<String, Participant> roster;

    public GroupTranscript(Map<String, Participant> roster) {
        this.roster = roster;
    }

    /** 取 transcript 尾部 {@code window} 条渲染成消息; window<=0 表示全取. */
    public List<Msg> toMessages(List<GroupMessage> transcript, int window) {
        List<GroupMessage> slice = tail(transcript, window);
        List<Msg> out = new ArrayList<Msg>(slice.size());
        for (GroupMessage m : slice) {
            out.add(toMsg(m));
        }
        return Collections.unmodifiableList(out);
    }

    public Msg toMsg(GroupMessage message) {
        Participant p = roster.get(message.getSenderId());
        boolean fromAgent = p != null && p.isAgent();
        String name = p == null ? message.getSenderName() : p.getDisplayName();
        return new Msg(fromAgent ? MessageRole.ASSISTANT : MessageRole.USER,
                message.getSenderId(),
                "[" + name + "] " + (message.getContent() == null ? "" : message.getContent()),
                MessageType.TEXT, null, null, Collections.<String, Object>emptyMap());
    }

    private static List<GroupMessage> tail(List<GroupMessage> transcript, int window) {
        if (window <= 0 || transcript.size() <= window) {
            return transcript;
        }
        return transcript.subList(transcript.size() - window, transcript.size());
    }
}
