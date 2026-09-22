package com.zifang.z.agent.kernel.state;

import java.util.Collections;
import java.util.Map;

/**
 * 状态快照. State.snapshot() 序列化产物, 可落盘/跨进程恢复.
 */
public final class StateSnapshot {

    private final String scope;
    private final Map<String, Object> data;
    private final long timestamp;

    public StateSnapshot(String scope, Map<String, Object> data) {
        this.scope = scope;
        this.data = data == null ? Collections.emptyMap() : Collections.unmodifiableMap(data);
        this.timestamp = System.currentTimeMillis();
    }

    public String getScope() {
        return scope;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }
}