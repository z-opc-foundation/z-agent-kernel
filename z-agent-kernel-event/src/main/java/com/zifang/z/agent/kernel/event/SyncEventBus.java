package com.zifang.z.agent.kernel.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 同步事件总线 — 默认实现. publish 在调用线程里同步触发所有匹配订阅者.
 *
 * <p>无队列, 无线程池, 适合:
 * <ul>
 *   <li>Spring 上下文内的事务内事件</li>
 *   <li>低频高保真事件 (agent run 生命周期)</li>
 *   <li>调试模式</li>
 * </ul>
 *
 * <p>对应 z-opc 老 EventBus 内未独立抽取的同步路径 (distilled).
 */
public class SyncEventBus implements EventBus {

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<Subscriber>();
    private volatile boolean closed = false;

    @Override
    public void publish(EventEnvelope<?> envelope) {
        if (closed) {
            throw new IllegalStateException("EventBus closed");
        }
        if (envelope == null || envelope.getEvent() == null) {
            throw new IllegalArgumentException("envelope + event required");
        }
        String type = envelope.getEvent().getType();
        for (Subscriber s : subscribers) {
            if (match(s.typePattern, type)) {
                try {
                    s.handler.accept(envelope);
                } catch (Throwable t) {
                    // 隔离单个订阅者失败, 不影响其他
                    System.err.println("[SyncEventBus] subscriber " + s.typePattern + " failed: " + t);
                }
            }
        }
    }

    @Override
    public void publish(String source, Event event) {
        publish(new EventEnvelope<Event>(event, source));
    }

    @Override
    public Subscription subscribe(String typePattern, Consumer<EventEnvelope<?>> handler) {
        if (typePattern == null || handler == null) {
            throw new IllegalArgumentException("typePattern + handler required");
        }
        Subscriber s = new Subscriber(typePattern, handler);
        subscribers.add(s);
        return new Subscription() {
            @Override
            public void cancel() {
                subscribers.remove(s);
            }
        };
    }

    @Override
    public void close() {
        closed = true;
        subscribers.clear();
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * typePattern 支持 {@code *} 通配符:
     * <ul>
     *   <li>{@code "agent.run.*"} 匹配 {@code "agent.run.started"} / {@code "agent.run.completed"}</li>
     *   <li>{@code "*"} 全匹配</li>
     *   <li>{@code "agent.run.started"} 精确匹配</li>
     * </ul>
     */
    static boolean match(String pattern, String type) {
        if (pattern == null || type == null) return false;
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return type.startsWith(prefix);
        }
        return pattern.equals(type);
    }

    private static final class Subscriber {
        final String typePattern;
        final Consumer<EventEnvelope<?>> handler;

        Subscriber(String typePattern, Consumer<EventEnvelope<?>> handler) {
            this.typePattern = typePattern;
            this.handler = handler;
        }
    }
}