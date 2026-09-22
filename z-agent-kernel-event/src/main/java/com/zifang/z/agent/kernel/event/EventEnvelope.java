package com.zifang.z.agent.kernel.event;

/**
 * 事件信封. 包一层 event + 来源 + 时间戳, 让 EventBus 可以按 envelope 维度做路由/重试/审计.
 *
 * <p>source: 事件源标识 (例如 agent run id).
 */
public final class EventEnvelope<T extends Event> {

    private final T event;
    private final String source;
    private final long timestamp;

    public EventEnvelope(T event, String source) {
        this.event = event;
        this.source = source;
        this.timestamp = System.currentTimeMillis();
    }

    public T getEvent() {
        return event;
    }

    public String getSource() {
        return source;
    }

    public long getTimestamp() {
        return timestamp;
    }
}