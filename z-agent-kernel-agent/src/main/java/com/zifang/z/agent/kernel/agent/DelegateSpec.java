package com.zifang.z.agent.kernel.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 子代理委派规格 — delegate_task 的约束声明.
 *
 * <p>语义对齐 hermes tools/delegate_tool.py: 子代理独立上下文, 预算按比例裁剪
 * (默认 ≤ 父 1/4), 有深度/宽度上限, child-only 工具(如 delegate 自身)默认剥离防递归.
 */
public final class DelegateSpec {

    public static final double DEFAULT_BUDGET_FRACTION = 0.25;
    public static final int DEFAULT_MAX_DEPTH = 3;
    public static final int DEFAULT_MAX_CONCURRENT = 3;

    private final String task;
    private final double budgetFraction;
    private final int maxDepth;
    private final int maxConcurrent;
    private final boolean async;
    private final Set<String> strippedTools;

    public DelegateSpec(String task) {
        this(task, DEFAULT_BUDGET_FRACTION, DEFAULT_MAX_DEPTH, DEFAULT_MAX_CONCURRENT, false, null);
    }

    public DelegateSpec(String task, double budgetFraction, int maxDepth, int maxConcurrent,
                        boolean async, Set<String> strippedTools) {
        this.task = task;
        this.budgetFraction = budgetFraction;
        this.maxDepth = maxDepth;
        this.maxConcurrent = maxConcurrent;
        this.async = async;
        this.strippedTools = strippedTools == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(strippedTools));
    }

    public String getTask() {
        return task;
    }

    public double getBudgetFraction() {
        return budgetFraction;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public boolean isAsync() {
        return async;
    }

    /** @return 派生子代理时应剥离的工具名 (默认剥离 delegate 自身, 调用方可追加). */
    public Set<String> getStrippedTools() {
        return strippedTools;
    }

    public DelegateSpec async() {
        return new DelegateSpec(task, budgetFraction, maxDepth, maxConcurrent, true, strippedTools);
    }

    public DelegateSpec stripTools(Set<String> tools) {
        Set<String> merged = new LinkedHashSet<String>(strippedTools);
        merged.addAll(tools);
        return new DelegateSpec(task, budgetFraction, maxDepth, maxConcurrent, async, merged);
    }

    /** child-only 工具名 — 任何子代理上下文里都不该出现. */
    public static Set<String> childOnlyTools() {
        return Collections.singleton("delegate_task");
    }
}
