package com.zifang.z.agent.kernel.agent.group;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次群聊 run 的结果.
 *
 * <p>finishedReason 三种: {@code converged} 策略不再点名任何人（正常收尾）、
 * {@code max_rounds} 撞了轮次上限、{@code failed} 中途抛异常.
 */
public final class GroupChatResult {

    private final List<GroupMessage> transcript;
    private final Map<String, Integer> speakCount;
    private final int rounds;
    private final String finishedReason;
    private final List<String> skippedResponders;
    private final Throwable error;

    public GroupChatResult(List<GroupMessage> transcript, Map<String, Integer> speakCount,
                           int rounds, String finishedReason,
                           List<String> skippedResponders, Throwable error) {
        this.transcript = Collections.unmodifiableList(transcript);
        this.speakCount = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(speakCount));
        this.rounds = rounds;
        this.finishedReason = finishedReason;
        this.skippedResponders = Collections.unmodifiableList(skippedResponders);
        this.error = error;
    }

    /** 完整群历史, 含触发本次 run 的第一条真人消息, 按 seq 升序. */
    public List<GroupMessage> getTranscript() {
        return transcript;
    }

    /** 每个 agent 在本次 run 里说了几句. */
    public Map<String, Integer> getSpeakCount() {
        return speakCount;
    }

    public int getRounds() {
        return rounds;
    }

    public String getFinishedReason() {
        return finishedReason;
    }

    /** 策略点名了、但 agent 表里查不到的 id —— 名册与 agent 表对不上时要看得见, 不能静默吞. */
    public List<String> getSkippedResponders() {
        return skippedResponders;
    }

    public Throwable getError() {
        return error;
    }

    public boolean isConverged() {
        return "converged".equals(finishedReason);
    }
}
