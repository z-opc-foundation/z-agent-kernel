package com.zifang.z.agent.kernel.tool;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ToolRegistryTest {

    private static Tool tool(final String name) {
        return new Tool() {
            public String getName() { return name; }
            public String getDescription() { return name + " desc"; }
            public java.util.Map<String, Object> getSchema() {
                return Collections.<String, Object>singletonMap("type", "object");
            }
            public ToolResult execute(java.util.Map<String, Object> arguments) {
                return ToolResult.text(name + " done");
            }
        };
    }

    @Test
    public void registerLookupAndGeneration() {
        ToolRegistry r = new ToolRegistry();
        assertEquals(0, r.size());
        long g0 = r.generation();
        r.register(tool("echo"), "basic");
        r.register(tool("exec"), "terminal");
        assertEquals(2, r.size());
        assertTrue(r.generation() > g0);
        assertEquals("echo", r.get("echo").getName());
        assertNull(r.get("nope"));
        assertTrue(r.has("exec"));
        assertEquals(Arrays.asList("echo", "exec"), r.names());
        assertEquals(1, r.byToolset("terminal").size());
        assertEquals("exec", r.byToolset("terminal").get(0).getName());
    }

    @Test
    public void byToolsetAndSnapshot() {
        ToolRegistry r = new ToolRegistry();
        r.register(tool("a1"), "file");
        r.register(tool("a2"), "file");
        r.register(tool("b1"), "web");
        assertEquals(2, r.byToolset("file").size());
        assertEquals(1, r.byToolset("web").size());
        assertEquals(0, r.byToolset("nope").size());
        java.util.Map<String, java.util.List<String>> snap = r.snapshotByToolset();
        assertEquals(Arrays.asList("a1", "a2"), snap.get("file"));
    }

    @Test
    public void overrideRequiresSameOwner() {
        ToolRegistry r = new ToolRegistry();
        r.register(tool("echo"), new ToolDescriptor("basic", false, null, "builtin"));
        // 同 owner 可覆写
        r.register(tool("echo"), new ToolDescriptor("basic", true, null, "builtin"));
        assertTrue(r.descriptor("echo").isParallelSafe());
        // 异 owner 覆写被拒
        try {
            r.register(tool("echo"), new ToolDescriptor("basic", false, null, "plugin-x"));
            fail("should reject cross-owner override");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("不能覆写"));
        }
        // 注销同理
        try {
            r.deregister("echo", "plugin-x");
            fail("should reject cross-owner deregister");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("不能注销"));
        }
        assertTrue(r.deregister("echo", "builtin"));
        assertFalse(r.has("echo"));
        assertFalse(r.deregister("echo", "builtin"));
    }

    @Test
    public void availabilityProbeTtlAndGrace() throws Exception {
        final AtomicInteger probes = new AtomicInteger();
        final AtomicInteger result = new AtomicInteger(1); // 1=ok 0=fail
        MutableClock clock = new MutableClock(0L);
        Tool t = tool("rg_search");
        ToolRegistry r = new ToolRegistry();
        r.register(t, new ToolDescriptor("search", false, new java.util.function.Supplier<Boolean>() {
            public Boolean get() {
                probes.incrementAndGet();
                return result.get() == 1;
            }
        }, "test", 1_000L, 60_000L, ToolDescriptor.NO_MAX_RESULT_CHARS, clock));
        ToolDescriptor d = r.descriptor("rg_search");

        assertTrue(d.isAvailable());
        clock.advance(999);
        assertTrue(d.isAvailable());
        assertEquals(1, probes.get()); // TTL 内命中缓存

        // TTL 边界: 恰好跨过 1000ms 才重探
        clock.advance(1);
        assertTrue(d.isAvailable());
        assertEquals(2, probes.get());

        result.set(0);
        // 仍在 TTL 内 → 连失败都探不到, 拿缓存 true
        clock.advance(1);
        assertTrue(d.isAvailable());
        assertEquals(2, probes.get());

        // TTL 到期: 距上次成功 60s 宽限窗内的失败 = 抖动 → 可用, 且**不缓存** ⇒ 下次调用重探
        clock.advance(1000);
        assertTrue(d.isAvailable());
        assertEquals(3, probes.get());
        assertTrue(d.isAvailable());
        assertEquals(4, probes.get());

        // 失败一直赖到宽限窗外 (锚点是"上次成功", 不是"上次探测") → 判不可用并缓存
        clock.advance(60_000);
        assertFalse(d.isAvailable());
        assertEquals(5, probes.get());
        // TTL 内不再探测, 直接拿缓存 false
        assertFalse(d.isAvailable());
        assertEquals(5, probes.get());
    }

    /** 可控毫秒钟: 让 TTL / 宽限窗边界可确定性跨过去, 不靠 sleep. */
    private static final class MutableClock implements java.util.function.LongSupplier {
        private long now;

        MutableClock(long start) {
            this.now = start;
        }

        void advance(long deltaMs) {
            now += deltaMs;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }

    /** 把探测缓存时间戳拨旧, 强制下一次 isAvailable 真正重探测 (等价于 TTL 过期). */
    private static void expireProbeCache(ToolDescriptor d) throws Exception {
        java.lang.reflect.Field f = ToolDescriptor.class.getDeclaredField("lastProbe");
        f.setAccessible(true);
        f.set(d, null);
    }

    @Test
    public void probeExceptionCountsAsFailure() throws Exception {
        MutableClock clock = new MutableClock(0L);
        ToolRegistry r = new ToolRegistry();
        r.register(tool("bad"), new ToolDescriptor("x", false, new java.util.function.Supplier<Boolean>() {
            public Boolean get() { throw new RuntimeException("boom"); }
        }, "test", 1_000L, 50L, ToolDescriptor.NO_MAX_RESULT_CHARS, clock));
        ToolDescriptor d = r.descriptor("bad");
        // 从未成功过 ⇒ 没有宽限锚点, 第一次失败就判不可用 (对齐 hermes: last-good 缺失就 honor failure)
        assertFalse(d.isAvailable());
        // 探针自己抛异常也不能把注册表炸了
        r.register(tool("err"), new ToolDescriptor("x", false, new java.util.function.Supplier<Boolean>() {
            public Boolean get() { throw new IllegalStateException("no docker"); }
        }, "test", 0L, 50L, ToolDescriptor.NO_MAX_RESULT_CHARS, clock));
        assertFalse(r.descriptor("err").isAvailable());
    }

    @Test
    public void probeRecoveryClearsGraceVerdict() {
        MutableClock clock = new MutableClock(0L);
        final AtomicInteger result = new AtomicInteger(0);
        ToolRegistry r = new ToolRegistry();
        r.register(tool("flip"), new ToolDescriptor("x", false, new java.util.function.Supplier<Boolean>() {
            public Boolean get() { return result.get() == 1; }
        }, "test", 10L, 1_000L, ToolDescriptor.NO_MAX_RESULT_CHARS, clock));
        ToolDescriptor d = r.descriptor("flip");
        assertFalse(d.isAvailable());   // 无锚点的首次失败 → 不可用
        result.set(1);
        clock.advance(11);
        assertTrue(d.isAvailable());    // 后端回来了就重新可用, 并写下新的 last-good
        result.set(0);
        clock.advance(11);
        assertTrue(d.isAvailable());    // 距刚成功 11ms < 1000ms 宽限 → 抖动
    }

    @Test
    public void invalidateProbeCacheDropsStaleVerdict() {
        MutableClock clock = new MutableClock(0L);
        final AtomicInteger probes = new AtomicInteger();
        ToolRegistry r = new ToolRegistry();
        r.register(tool("inv"), new ToolDescriptor("x", false, new java.util.function.Supplier<Boolean>() {
            public Boolean get() {
                probes.incrementAndGet();
                return Boolean.TRUE;
            }
        }, "test", 60_000L, 60_000L, ToolDescriptor.NO_MAX_RESULT_CHARS, clock));
        ToolDescriptor d = r.descriptor("inv");
        assertTrue(d.isAvailable());
        assertTrue(d.isAvailable());
        assertEquals(1, probes.get());
        d.invalidateProbeCache();       // 对齐 hermes invalidate_check_fn_cache(): 配置变了要立刻重探
        assertTrue(d.isAvailable());
        assertEquals(2, probes.get());
    }

    @Test
    public void availableToolsFiltersUnavailable() throws Exception {
        ToolRegistry r = new ToolRegistry();
        r.register(tool("ok"), "t");
        r.register(tool("gone"), new ToolDescriptor("t", false, new java.util.function.Supplier<Boolean>() {
            public Boolean get() { return false; }
        }, "test"));
        // 探测两次 (中间强制缓存过期) 让它判死
        ToolDescriptor d = r.descriptor("gone");
        d.isAvailable();
        expireProbeCache(d);
        d.isAvailable();
        java.util.List<Tool> avail = r.availableTools();
        assertEquals(1, avail.size());
        assertEquals("ok", avail.get(0).getName());
    }

    @Test
    public void distributionsFilterByToolsetAndDisabled() {
        ToolRegistry r = new ToolRegistry();
        r.register(tool("read"), "file");
        r.register(tool("write"), "file");
        r.register(tool("search"), "search");
        ToolsetDistributions dist = new ToolsetDistributions(r,
                new HashSet<String>(Arrays.asList("file")));
        assertEquals(2, dist.exposedTools(null).size());
        assertEquals(1, dist.exposedTools(Collections.singleton("write")).size());
        assertTrue(dist.isExposed("read", null));
        assertFalse(dist.isExposed("write", Collections.singleton("write")));
        assertFalse(dist.isExposed("search", null)); // toolset 不在白名单
        assertFalse(dist.isExposed("ghost", null));
        assertEquals(Collections.singleton("file"), dist.catalog().keySet());
    }

    /** 真 deregister: 槽位消失 (不是被同名 stub 盖掉), 且 generation 递增. */
    @Test
    public void deregisterReallyRemovesAndBumpsGeneration() {
        ToolRegistry r = new ToolRegistry();
        r.register(tool("a"), new ToolDescriptor("mcp-fs", false, null, "mcp:fs"));
        r.register(tool("b"), new ToolDescriptor("mcp-fs", false, null, "mcp:fs"));
        long g0 = r.generation();
        List<String> namesBefore = r.names();
        assertTrue(namesBefore.contains("a"));

        assertTrue(r.deregister("a", "mcp:fs"));
        assertFalse("槽位必须真的空掉, 而不是留个同名替身", r.has("a"));
        assertNull(r.get("a"));
        assertFalse("descriptor 也一并消失", r.descriptor("a") != null);
        assertEquals(Arrays.asList("b"), r.names());
        assertTrue("注销必须让上层 schema 缓存失效", r.generation() > g0);
        // 幂等: 再注销同一个不存在的名字 → false, 且不吃代际
        long g1 = r.generation();
        assertFalse(r.deregister("a", "mcp:fs"));
        assertEquals(g1, r.generation());
    }

    /** toolset 整体注销 = MCP list_changed 的 nuk-and-repave; 连"上次没记住"的漏网名字也清掉. */
    @Test
    public void deregisterByToolsetClearsWholeToolset() {
        ToolRegistry r = new ToolRegistry();
        r.register(tool("mcp-fs-read"), new ToolDescriptor("mcp-fs", false, null, "mcp:fs"));
        r.register(tool("mcp-fs-write"), new ToolDescriptor("mcp-fs", false, null, "mcp:fs"));
        r.register(tool("exec"), new ToolDescriptor("terminal", false, null, "builtin"));
        long g0 = r.generation();

        List<String> removed = r.deregisterByToolset("mcp-fs", "mcp:fs");
        assertEquals(Arrays.asList("mcp-fs-read", "mcp-fs-write"), removed);
        assertEquals(Arrays.asList("exec"), r.names());
        assertEquals(0, r.byToolset("mcp-fs").size());
        assertTrue(r.generation() >= g0 + 2);
        // 别的 owner 想借同名的 toolset 清别人家 → 拒
        r.register(tool("other"), new ToolDescriptor("mixed", false, null, "builtin"));
        try {
            r.deregisterByToolset("mixed", "intruder");
            fail("should reject cross-owner toolset wipe");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("不能注销 toolset"));
        }
        assertEquals(0, r.deregisterByToolset("nope", "builtin").size());
    }

    /** 结果上限: 未声明走全局缺省 (hermes DEFAULT_RESULT_SIZE_CHARS=100_000), 声明值优先, inf 可表达. */
    @Test
    public void maxResultCharsResolutionMirrorsHermes() {
        ToolRegistry r = new ToolRegistry();
        r.register(tool("plain"), "terminal");
        assertEquals(ToolRegistry.DEFAULT_RESULT_SIZE_CHARS,
                r.maxResultChars("plain", ToolRegistry.DEFAULT_RESULT_SIZE_CHARS));
        r.register(tool("web"), ToolDescriptor.withMaxResult("web", "builtin", 5_000L));
        assertEquals(5_000L, r.maxResultChars("web", ToolRegistry.DEFAULT_RESULT_SIZE_CHARS));
        r.register(tool("read_file"), ToolDescriptor.withMaxResult("file", "builtin",
                ToolDescriptor.UNBOUNDED_RESULT_CHARS));
        assertEquals(Long.MAX_VALUE, r.maxResultChars("read_file", ToolRegistry.DEFAULT_RESULT_SIZE_CHARS));
        // 不存在的工具也走缺省, 不抛
        assertEquals(123L, r.maxResultChars("ghost", 123L));
    }

    /** 注册顺序 = 遍历顺序; 覆写不换位置 (schema 前缀必须逐字节稳定). */
    @Test
    public void registrationOrderIsStable() {
        ToolRegistry r = new ToolRegistry();
        r.register(tool("read"), "file");
        r.register(tool("exec"), "terminal");
        r.register(tool("web"), "web");
        r.register(tool("read"), "file"); // 同 owner 覆写
        assertEquals(Arrays.asList("read", "exec", "web"), r.names());
        r.deregister("exec", "kernel");
        assertEquals(Arrays.asList("read", "web"), r.names());
    }
}
