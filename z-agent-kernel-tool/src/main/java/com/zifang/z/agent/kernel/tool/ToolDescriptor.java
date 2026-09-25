package com.zifang.z.agent.kernel.tool;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 工具注册描述 — 除 Tool 本体外的横切元信息.
 *
 * <p>语义对齐 hermes tools/registry.py:
 * <ul>
 *   <li>{@code toolset}: 工具所属分组 (terminal/file/web/...), 入口面按 toolset 过滤.
 *       MCP 桥用 {@code mcp-<server>} — hermes registry.deregister 也是按这个前缀放行
 *       "自己 nuk-and-repave 自己"的例外 (registry.py:486)</li>
 *   <li>{@code parallelSafe}: 该工具是否可与其他工具并行批执行 (有副作用/写路径的工具必须 false).
 *       注意这只是**声明**; 真正的分批判定在派发侧 (hermes agent/tool_dispatch_helpers.py,
 *       z-bot agent/BotAgent.java 的 executeBatch)</li>
 *   <li>{@code availabilitySupplier}: 可用性探测 (check_fn), TTL 缓存 + 瞬时失败宽限 —
 *       对齐 hermes {@code _CHECK_FN_TTL_SECONDS=30.0} / {@code _CHECK_FN_FAILURE_GRACE_SECONDS=60.0}
 *       (registry.py:143/147). 宽限语义是**时间窗**而非"连续两次失败":
 *       上次成功 60s 内的失败当作抖动, 返回 true 且**不写缓存**, 下次调用重新探测;
 *       宽限窗外的失败照常缓存 false, 免得真宕掉的后端每轮都被重试</li>
 *   <li>{@code maxResultChars}: 单工具结果上限 (hermes {@code max_result_size_chars},
 *       registry.py:650 get_max_result_size). 0 以下 = 未声明, 由注册表缺省兜底;
 *       {@link #UNBOUNDED_RESULT_CHARS} = 不设限 (hermes 给 read_file 钉 inf,
 *       防 persist→read→persist 死循环, budget_config.py:19)</li>
 *   <li>{@code owner}: 注册方标识, 覆写/注销需同 owner 校验 (插件不允许悄悄顶掉内建工具)</li>
 * </ul>
 */
public final class ToolDescriptor {

    /** 可用性探测结果缓存 TTL: 30s (对齐 hermes _CHECK_FN_TTL_SECONDS=30.0). */
    public static final long PROBE_TTL_MS = 30_000L;

    /** 上次成功之后多久内的失败算抖动 (对齐 hermes _CHECK_FN_FAILURE_GRACE_SECONDS=60.0). */
    public static final long PROBE_FAILURE_GRACE_MS = 60_000L;

    /** 结果上限的"不设限"哨兵 (hermes 的 float('inf')). */
    public static final long UNBOUNDED_RESULT_CHARS = Long.MAX_VALUE;

    /** 未声明结果上限 — 由注册表缺省值兜底. */
    public static final long NO_MAX_RESULT_CHARS = -1L;

    private static final LongSupplier SYSTEM_CLOCK = new LongSupplier() {
        @Override
        public long getAsLong() {
            // 单调钟: 系统时间被调也不会让 TTL 提前/倒退
            return System.nanoTime() / 1_000_000L;
        }
    };

    private final String toolset;
    private final boolean parallelSafe;
    private final Supplier<Boolean> availabilitySupplier;
    private final String owner;
    private final long probeTtlMs;
    private final long probeFailureGraceMs;
    private final long maxResultChars;
    private final LongSupplier clock;

    /** 最近一次探测结论与其时间戳 (TTL 用). */
    private volatile Boolean lastProbe;
    private volatile long probeAt;
    /** 最近一次**成功**的时间戳 — 宽限窗的锚点 (hermes _check_fn_last_good). */
    private volatile Long lastGoodAt;

    public ToolDescriptor(String toolset, boolean parallelSafe, Supplier<Boolean> availabilitySupplier, String owner) {
        this(toolset, parallelSafe, availabilitySupplier, owner,
                PROBE_TTL_MS, PROBE_FAILURE_GRACE_MS, NO_MAX_RESULT_CHARS, SYSTEM_CLOCK);
    }

    /**
     * 全参构造.
     *
     * @param probeTtlMs           探测结论缓存时长 (&lt;=0 表示不缓存, 每次都探)
     * @param probeFailureGraceMs  成功后多久内的失败算抖动
     * @param maxResultChars       单工具结果字符上限; {@link #NO_MAX_RESULT_CHARS}=未声明
     * @param clock                毫秒时钟 (测试注入假钟以确定性跨越 TTL/宽限边界); null → 系统单调钟
     */
    public ToolDescriptor(String toolset, boolean parallelSafe, Supplier<Boolean> availabilitySupplier, String owner,
                          long probeTtlMs, long probeFailureGraceMs, long maxResultChars, LongSupplier clock) {
        this.toolset = toolset == null ? "default" : toolset;
        this.parallelSafe = parallelSafe;
        this.availabilitySupplier = availabilitySupplier;
        this.owner = owner == null ? "kernel" : owner;
        this.probeTtlMs = probeTtlMs;
        this.probeFailureGraceMs = probeFailureGraceMs;
        this.maxResultChars = maxResultChars;
        this.clock = clock == null ? SYSTEM_CLOCK : clock;
    }

    public static ToolDescriptor simple(String toolset, String owner) {
        return new ToolDescriptor(toolset, false, null, owner);
    }

    /** 带结果上限的简版描述. */
    public static ToolDescriptor withMaxResult(String toolset, String owner, long maxResultChars) {
        return new ToolDescriptor(toolset, false, null, owner,
                PROBE_TTL_MS, PROBE_FAILURE_GRACE_MS, maxResultChars, null);
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

    /** @return 声明的结果字符上限; {@link #NO_MAX_RESULT_CHARS} 表示未声明. */
    public long getMaxResultChars() {
        return maxResultChars;
    }

    /** 是否挂了可用性探测器 (注册表据此判断"这张表需要按可用性过滤 schema"). */
    public boolean hasAvailabilityProbe() {
        return availabilitySupplier != null;
    }

    /**
     * @return 工具当前是否可用. 无探测函数 → 恒可用.
     *         缓存期内直接返回缓存; 过期才真正探测.
     *         宽限窗内的失败按抖动处理 (返回 true 且不缓存), 窗外的失败缓存 false.
     */
    public boolean isAvailable() {
        if (availabilitySupplier == null) {
            return true;
        }
        long now = clock.getAsLong();
        Boolean cached = lastProbe;
        if (cached != null && now - probeAt < probeTtlMs) {
            return cached;
        }
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(availabilitySupplier.get());
        } catch (RuntimeException e) {
            ok = false;
        } catch (Error e) {
            // 探测器把 Error 抛出来也当"这次没探到", 不能让它掀掉注册表
            ok = false;
        }
        if (ok) {
            lastGoodAt = Long.valueOf(now);
            lastProbe = Boolean.TRUE;
            probeAt = now;
            return true;
        }
        Long lastGood = lastGoodAt;
        if (lastGood != null && now - lastGood.longValue() < probeFailureGraceMs) {
            // 宽限窗内的瞬时失败: 保住工具, 且不写缓存 ⇒ 下次调用会重新探
            lastProbe = null;
            return true;
        }
        lastProbe = Boolean.FALSE;
        probeAt = now;
        return false;
    }

    /** 显式丢弃探测缓存 (对齐 hermes invalidate_check_fn_cache, registry.py:209). */
    public void invalidateProbeCache() {
        lastProbe = null;
        lastGoodAt = null;
        probeAt = 0L;
    }
}
