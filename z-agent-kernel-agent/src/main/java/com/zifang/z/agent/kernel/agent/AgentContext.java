package com.zifang.z.agent.kernel.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 显式运行状态对象 — 替代"上帝类"里的内联横向状态.
 *
 * <p>一个 AgentContext 挂: 预算 / 中断标志 / steer 队列 / 委派深度 / 任意 attribute.
 * 子代理通过 {@link #newChild(DelegateSpec)} 派生: 继承中断传播策略, 预算按 fraction 裁剪, 深度 +1.
 */
public final class AgentContext {

    private final IterationBudget budget;
    private final InterruptFlag interrupt;
    private final SteerQueue steer;
    private final int depth;
    private final int maxConcurrentChildren;
    private final Map<String, Object> attributes;

    private AgentContext(IterationBudget budget, InterruptFlag interrupt, SteerQueue steer,
                         int depth, int maxConcurrentChildren, Map<String, Object> attributes) {
        this.budget = budget;
        this.interrupt = interrupt;
        this.steer = steer;
        this.depth = depth;
        this.maxConcurrentChildren = maxConcurrentChildren;
        this.attributes = Collections.unmodifiableMap(new HashMap<String, Object>(attributes));
    }

    /** 根上下文. */
    public static AgentContext root(IterationBudget budget) {
        return new AgentContext(budget, new InterruptFlag(), new SteerQueue(), 0, 3,
                Collections.<String, Object>emptyMap());
    }

    /** 按委派规格派生子上下文; spec 的 depthLimit 不够则抛 IllegalStateException. */
    public AgentContext newChild(DelegateSpec spec) {
        if (depth >= spec.getMaxDepth()) {
            throw new IllegalStateException("delegate depth limit reached: " + depth + " >= " + spec.getMaxDepth());
        }
        return new AgentContext(budget.childBudget(spec.getBudgetFraction()), new InterruptFlag(),
                new SteerQueue(), depth + 1,
                Math.min(maxConcurrentChildren, spec.getMaxConcurrent()), attributes);
    }

    public IterationBudget budget() {
        return budget;
    }

    public InterruptFlag interrupt() {
        return interrupt;
    }

    public SteerQueue steer() {
        return steer;
    }

    /** @return 当前委派深度 (根=0). */
    public int depth() {
        return depth;
    }

    public int maxConcurrentChildren() {
        return maxConcurrentChildren;
    }

    public Object attribute(String key) {
        return attributes.get(key);
    }

    /** @return 带上新增 attribute 的浅拷贝 (不可变对象风格, 便于跨线程快照). */
    public AgentContext with(String key, Object value) {
        Map<String, Object> next = new HashMap<String, Object>(attributes);
        next.put(key, value);
        return new AgentContext(budget, interrupt, steer, depth, maxConcurrentChildren, next);
    }
}
