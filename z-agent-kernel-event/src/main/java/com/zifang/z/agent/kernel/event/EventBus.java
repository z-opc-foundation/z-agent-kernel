package com.zifang.z.agent.kernel.event;

import java.util.function.Consumer;

/**
 * 事件总线 SPI. 上层通过 publish 发送事件, 通过 subscribe 注册消费者.
 *
 * <p>publish: 同步发布, 失败抛异常; 异步语义由实现方决定.
 * <p>subscribe: 注册 type-prefix 匹配 + 消费者 lambda; 返回 Subscription 可取消订阅.
 */
public interface EventBus {

    void publish(EventEnvelope<?> envelope);

    void publish(String source, Event event);

    Subscription subscribe(String typePattern, Consumer<EventEnvelope<?>> handler);

    void close();

    interface Subscription {
        void cancel();
    }
}