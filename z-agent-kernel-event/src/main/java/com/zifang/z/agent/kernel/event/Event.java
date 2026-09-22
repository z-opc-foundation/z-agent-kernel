package com.zifang.z.agent.kernel.event;

import java.util.Map;

/**
 * 事件接口. kernel.event 体系里所有事件都实现此接口.
 *
 * <p>type: 事件类型 (String, 推荐点分隔的 namespace, 例如 "agent.run.started").
 * <p>timestamp: 事件创建时间 (epoch millis).
 * <p>attributes: 透传 KV, 用于路由/过滤.
 */
public interface Event {

    String getType();

    long getTimestamp();

    Map<String, Object> getAttributes();

    default Object getPayload() {
        return null;
    }
}