package com.zifang.z.agent.kernel.agent;

/**
 * 协作式软中断标志 — 线程安全, 中断只在迭代/工具边界被检查生效, 不强杀线程.
 *
 * <p>语义对齐 hermes tools/interrupt.py: 请求方(用户 /stepper)置位,
 * 主循环在每次 LLM 调用前和工具执行前调 {@link #checkpoint()} 决定是否停下.
 */
public final class InterruptFlag {

    private volatile boolean interrupted;
    private volatile String reason;

    public void request(String reason) {
        this.reason = reason == null ? "interrupted" : reason;
        this.interrupted = true;
    }

    public boolean isInterrupted() {
        return interrupted;
    }

    public String reason() {
        return reason;
    }

    /** 循环边界检查点; 命中即抛出, 让主循环走"被打断"收尾分支. */
    public void checkpoint() {
        if (interrupted) {
            throw new AgentInterruptedException(reason);
        }
    }

    /** 清除(新一轮请求开始时). */
    public void reset() {
        interrupted = false;
        reason = null;
    }

    /** 被打断时主循环抛出的受查外信号 — 上层捕获后做会话收尾, 不是错误. */
    public static class AgentInterruptedException extends RuntimeException {
        public AgentInterruptedException(String reason) {
            super(reason);
        }
    }
}
