package com.zifang.z.agent.kernel.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Steer 队列 — 用户在 agent 运行中插话, 不打断当前工具, 在工具间隙注入.
 *
 * <p>语义对齐 hermes /steer: 文本入队, 下次工具调用返回后以 {@code [User steer]:} 前缀
 * 附加进消息流. 三档不打断策略由上层组合: /queue(下一轮) → /steer(工具间隙) → /stop(硬打断).
 */
public final class SteerQueue {

    private final List<String> pending = new ArrayList<String>();

    public synchronized void add(String message) {
        if (message != null && !message.trim().isEmpty()) {
            pending.add(message.trim());
        }
    }

    /** @return 取走全部待注入的 steer 文本(原顺序), 没有则空列表. */
    public synchronized List<String> drain() {
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>(pending);
        pending.clear();
        return out;
    }

    public synchronized boolean hasPending() {
        return !pending.isEmpty();
    }

    public synchronized void clear() {
        pending.clear();
    }
}
