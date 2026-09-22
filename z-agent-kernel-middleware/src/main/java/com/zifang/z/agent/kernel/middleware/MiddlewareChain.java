package com.zifang.z.agent.kernel.middleware;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 中间件链 — 把多个 Middleware 按 order 串成调用链.
 *
 * <p>before: 按 order 从小到大依次调用, 任一返回 false 中断后续 before 和 action.
 * <p>after: 按 order 从大到小依次调用(栈逆序), 即使前面失败也会调.
 *
 * <p>对应 z-opc 老 z-agent-engine 未单独抽取的 chain 机制 (distilled).
 */
public class MiddlewareChain {

    private final List<Middleware> middlewares;

    public MiddlewareChain(List<Middleware> middlewares) {
        List<Middleware> sorted = new ArrayList<Middleware>(middlewares);
        Collections.sort(sorted, new Comparator<Middleware>() {
            @Override
            public int compare(Middleware a, Middleware b) {
                return Integer.compare(a.order(), b.order());
            }
        });
        this.middlewares = Collections.unmodifiableList(sorted);
    }

    public static MiddlewareChain of(Middleware... ms) {
        return new MiddlewareChain(java.util.Arrays.asList(ms));
    }

    /**
     * 执行前拦截: 全部返回 true 才继续 action.
     */
    public boolean beforeAll(String name, Map<String, Object> attributes) {
        Middleware.MiddlewareContext ctx = new SimpleContext(name, attributes == null ? new HashMap<String, Object>() : attributes);
        for (Middleware m : middlewares) {
            try {
                if (!m.before(ctx)) {
                    return false;
                }
            } catch (Throwable t) {
                System.err.println("[MiddlewareChain] " + m.getName() + ".before threw: " + t);
                return false;
            }
        }
        return true;
    }

    /**
     * 执行后拦截(逆序). error 为 null 表示成功.
     */
    public void afterAll(String name, Map<String, Object> attributes, Throwable error) {
        Middleware.MiddlewareContext ctx = new SimpleContext(name, attributes == null ? new HashMap<String, Object>() : attributes);
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            Middleware m = middlewares.get(i);
            try {
                m.after(ctx, error);
            } catch (Throwable t) {
                System.err.println("[MiddlewareChain] " + m.getName() + ".after threw: " + t);
            }
        }
    }

    /**
     * 包裹一段 action: beforeAll → action → afterAll, action 抛异常时 afterAll 仍触发.
     */
    public void execute(String name, Map<String, Object> attributes, Action action) {
        if (!beforeAll(name, attributes)) {
            afterAll(name, attributes, new MiddlewareAbortedException("chain aborted: " + name));
            return;
        }
        Throwable error = null;
        try {
            action.run();
        } catch (Throwable t) {
            error = t;
        }
        afterAll(name, attributes, error);
        if (error != null && !(error instanceof MiddlewareAbortedException)) {
            throw new RuntimeException(error);
        }
    }

    public int size() {
        return middlewares.size();
    }

    public List<String> names() {
        List<String> n = new ArrayList<String>(middlewares.size());
        for (Middleware m : middlewares) n.add(m.getName());
        return n;
    }

    public interface Action {
        void run();
    }

    public static class MiddlewareAbortedException extends RuntimeException {
        public MiddlewareAbortedException(String msg) {
            super(msg);
        }
    }

    private static final class SimpleContext implements Middleware.MiddlewareContext {
        private final String name;
        private final Map<String, Object> attributes;

        SimpleContext(String name, Map<String, Object> attributes) {
            this.name = name;
            this.attributes = attributes;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public void setAttribute(String key, Object value) {
            attributes.put(key, value);
        }
    }
}