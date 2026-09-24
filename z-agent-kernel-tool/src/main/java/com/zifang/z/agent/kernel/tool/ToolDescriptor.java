package com.zifang.z.agent.kernel.tool;

import java.util.function.Supplier;

/**
 * 工具注册描述 — 除 Tool 本体外的横切元信息.
 *
 * <p>语义对齐 hermes tools/registry.py:
 * <ul>
 *   <li>{@code toolset}: 工具所属分组 (terminal/file/web/...), 入口面按 toolset 过滤</li>
 *   <li>{@code parallelSafe}: 该工具是否可与其他工具并行批执行 (有副作用/写路径的工具必须 false)</li>
 *   <li>{@code availabilitySupplier}: 可用性探测 (如二进制是否存在), 带 TTL 缓存;
 *       连续两次探测失败才判定不可用, 单次失败只进宽限期 — 防瞬时抖动把工具从子代理身上"抖掉"</li>
 *   <li>{@code owner}: 注册方标识, 覆写/注销需同 owner 校验 (插件不允许悄悄顶掉内建工具)</li>
 * </ul>
 */
public final class ToolDescriptor {

    /** 可用性探测结果缓存 TTL: 30s (对齐 hermes). */
    public static final long PROBE_TTL_MS = 30_000L;

    private final String toolset;
    private final boolean parallelSafe;
    private final Supplier<Boolean> availabilitySupplier;
    private final String owner;

    private volatile Boolean lastProbe;
    private volatile long probeAt;
    private volatile int consecutiveFailures;

    public ToolDescriptor(String toolset, boolean parallelSafe, Supplier<Boolean> availabilitySupplier, String owner) {
        this.toolset = toolset == null ? "default" : toolset;
        this.parallelSafe = parallelSafe;
        this.availabilitySupplier = availabilitySupplier;
        this.owner = owner == null ? "kernel" : owner;
    }

    public static ToolDescriptor simple(String toolset, String owner) {
        return new ToolDescriptor(toolset, false, null, owner);
    }

    public String getToolset() {
        return toolset;
    }

    public boolean isParallelSafe() {
        return parallelSafe;
    }

    public String getOwner() {
        return owner;
    }

    /**
     * @return 工具当前是否可用. 无探测函数 → 恒可用.
     *         缓存期内直接返回缓存; 过期才真正探测.
     *         单次失败不降级(宽限), 连续两次失败才判不可用.
     */
    public boolean isAvailable() {
        if (availabilitySupplier == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Boolean cached = lastProbe;
        if (cached != null && now - probeAt < PROBE_TTL_MS) {
            return cached;
        }
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(availabilitySupplier.get());
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok) {
            consecutiveFailures = 0;
        } else {
            consecutiveFailures++;
        }
        // 连续两次失败才把结果当真 (宽限期语义); 刷新缓存时间戳防探测风暴
        boolean verdict = ok || consecutiveFailures < 2;
        lastProbe = verdict;
        probeAt = now;
        return verdict;
    }
}
