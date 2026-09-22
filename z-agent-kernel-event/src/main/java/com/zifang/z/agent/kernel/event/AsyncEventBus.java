package com.zifang.z.agent.kernel.event;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 异步事件总线 — 默认实现. publish 后立即返回, 事件在后台线程池异步分发.
 *
 * <p>线程池大小默认 4 (适合大多数 agent 场景); 可在构造时自定义.
 *
 * <p>close 会 shutdown 线程池, 拒绝后续 publish.
 */
public class AsyncEventBus implements EventBus {

    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<Subscriber>();
    private final ExecutorService executor;
    private volatile boolean closed = false;

    public AsyncEventBus() {
        this(Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "kernel-event-bus-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }));
    }

    public AsyncEventBus(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public void publish(final EventEnvelope<?> envelope) {
        if (closed) {
            throw new IllegalStateException("EventBus closed");
        }
        if (envelope == null || envelope.getEvent() == null) {
            throw new IllegalArgumentException("envelope + event required");
        }
        final String type = envelope.getEvent().getType();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (Subscriber s : subscribers) {
                    if (SyncEventBus.match(s.typePattern, type)) {
                        try {
                            s.handler.accept(envelope);
                        } catch (Throwable t) {
                            System.err.println("[AsyncEventBus] subscriber " + s.typePattern + " failed: " + t);
                        }
                    }
                }
            }
        });
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
        executor.shutdown();
        subscribers.clear();
    }

    public int subscriberCount() {
        return subscribers.size();
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