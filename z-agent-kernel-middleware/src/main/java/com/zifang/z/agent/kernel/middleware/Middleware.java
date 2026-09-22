package com.zifang.z.agent.kernel.middleware;

import java.util.Map;

/**
 * 中间件 SPI. agent 调用链拦截点, 用于鉴权/限流/日志/指标.
 *
 * <p>before: 调用前执行, 返回 false 中断链路.
 * <p>after: 调用后执行 (无论成功失败).
 * <p>order: 数值越小越靠前.
 */
public interface Middleware {

    String getName();

    int order();

    boolean before(MiddlewareContext ctx);

    void after(MiddlewareContext ctx, Throwable error);

    interface MiddlewareContext {
        String getName();

        Map<String, Object> getAttributes();

        void setAttribute(String key, Object value);
    }
}