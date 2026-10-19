package com.zifang.z.agent.kernel.agent.group;

import java.util.Collections;
import java.util.List;

/**
 * 群聊里的一条发言.
 *
 * <p>seq: 群内单调递增序号, 由 {@link GroupChatRunner} 分配 —— 顺序是群聊语义的一部分,
 * 不是日志细节: agent 的上下文按 seq 截断, 派单也要引用 target_seq.
 * <p>mentions: 本条点名到的 agent id（谁被 @ 了）. 路由策略读它决定谁该开口.
 */
public final class GroupMessage {

    private final long seq;
    private final String senderId;
    private final String senderName;
    private final String content;
    private final List<String> mentions;

    public GroupMessage(long seq, String senderId, String senderName, String content, List<String> mentions) {
        this.seq = seq;
        this.senderId = senderId;
        this.senderName = senderName == null || senderName.isEmpty() ? senderId : senderName;
        this.content = content;
        this.mentions = mentions == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(mentions);
    }

    public static GroupMessage of(long seq, String senderId, String senderName, String content) {
        return new GroupMessage(seq, senderId, senderName, content, null);
    }

    /** 真人点名发言: content 里可以带 @xxx, 也可以显式给 mentions. */
    public static GroupMessage mentioned(long seq, String senderId, String senderName,
                                         String content, List<String> mentions) {
        return new GroupMessage(seq, senderId, senderName, content, mentions);
    }

    public long getSeq() {
        return seq;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getContent() {
        return content;
    }

    public List<String> getMentions() {
        return mentions;
    }

    @Override
    public String toString() {
        return "#" + seq + " " + senderName + ": " + content;
    }
}
