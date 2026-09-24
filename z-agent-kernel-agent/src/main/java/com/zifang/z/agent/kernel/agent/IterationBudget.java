package com.zifang.z.agent.kernel.agent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 迭代/token 预算 — ReAct 主循环的硬上限与记账.
 *
 * <p>语义对齐 hermes conversation_loop:
 * <ul>
 *   <li>{@code apiCalls < maxIterations && tokensUsed < maxTokens} 才允许下一轮 LLM 调用</li>
 *   <li>预算耗尽时仍允许 grace 次"收尾调用"(让模型有机会说结束语, 默认 1)</li>
 *   <li>全部线程安全, 主/子 agent 可各自持有或共享</li>
 * </ul>
 */
public final class IterationBudget {

    private final int maxIterations;
    private final long maxTokens;
    private final int graceCalls;

    private final AtomicInteger apiCalls = new AtomicInteger();
    private final AtomicInteger graceUsed = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    public IterationBudget(int maxIterations, long maxTokens) {
        this(maxIterations, maxTokens, 1);
    }

    public IterationBudget(int maxIterations, long maxTokens, int graceCalls) {
        this.maxIterations = maxIterations;
        this.maxTokens = maxTokens;
        this.graceCalls = graceCalls;
    }

    /** @return 是否还允许发起一次 LLM 调用(含 grace 收尾逻辑). */
    public boolean canCall() {
        if (apiCalls.get() < maxIterations && tokensUsed() < maxTokens) {
            return true;
        }
        return graceUsed.get() < graceCalls;
    }

    /** 发起调用前登记; 如果走的是 grace 通道同时扣减 grace 名额. */
    public void onCall() {
        if (apiCalls.getAndIncrement() < maxIterations && tokensUsed() < maxTokens) {
            return;
        }
        graceUsed.incrementAndGet();
    }

    public void recordTokens(long input, long output) {
        inputTokens.addAndGet(Math.max(0, input));
        outputTokens.addAndGet(Math.max(0, output));
    }

    public long tokensUsed() {
        return inputTokens.get() + outputTokens.get();
    }

    public int apiCalls() {
        return apiCalls.get();
    }

    public int maxIterations() {
        return maxIterations;
    }

    public long maxTokens() {
        return maxTokens;
    }

    public long inputTokens() {
        return inputTokens.get();
    }

    public long outputTokens() {
        return outputTokens.get();
    }

    /** @return 按比例裁剪出来的子预算 (Hermes: 子 agent 预算 ≤ 父的 1/4). */
    public IterationBudget childBudget(double fraction) {
        double f = Math.min(1.0, Math.max(0.05, fraction));
        return new IterationBudget(
                Math.max(1, (int) Math.floor(maxIterations * f)),
                Math.max(1L, (long) Math.floor(maxTokens * f)),
                graceCalls);
    }
}
