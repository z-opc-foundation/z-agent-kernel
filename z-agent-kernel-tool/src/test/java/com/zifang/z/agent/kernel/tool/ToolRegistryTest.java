package com.zifang.z.agent.kernel.tool;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
        Tool t = tool("rg_search");
        ToolRegistry r = new ToolRegistry();
        r.register(t, new ToolDescriptor("search", false, new java.util.function.Supplier<Boolean>() {
            public Boolean get() {
                probes.incrementAndGet();
                return result.get() == 1;
            }
        }, "test"));
        ToolDescriptor d = r.descriptor("rg_search");

        assertTrue(d.isAvailable());
        assertTrue(d.isAvailable());
        assertEquals(1, probes.get()); // TTL 内命中缓存

        result.set(0);
        Thread.sleep(20);
        // 把缓存时间戳拨旧, 强制重探测
        java.lang.reflect.Field f = ToolDescriptor.class.getDeclaredField("probeAt");
        f.setAccessible(true);
        f.setLong(d, System.currentTimeMillis() - ToolDescriptor.PROBE_TTL_MS - 1);

        // 第 1 次失败: 宽限期仍算可用
        assertTrue(d.isAvailable());
        assertEquals(2, probes.get());
        // 第 2 次失败 (仍在 TTL 内, 拨旧强制探测): 连续两次 → 判不可用
        f.setLong(d, System.currentTimeMillis() - ToolDescriptor.PROBE_TTL_MS - 1);
        assertFalse(d.isAvailable());
        assertEquals(3, probes.get());

        // TTL 内不再探测, 直接拿缓存 false
        assertFalse(d.isAvailable());
        assertEquals(3, probes.get());
    }

    /** 把探测缓存时间戳拨旧, 强制下一次 isAvailable 真正重探测 (等价于 TTL 过期). */
    private static void expireProbeCache(ToolDescriptor d) throws Exception {
        java.lang.reflect.Field f = ToolDescriptor.class.getDeclaredField("probeAt");
        f.setAccessible(true);
        f.setLong(d, System.currentTimeMillis() - ToolDescriptor.PROBE_TTL_MS - 1);
    }

    @Test
    public void probeExceptionCountsAsFailure() throws Exception {
        ToolRegistry r = new ToolRegistry();
        r.register(tool("bad"), new ToolDescriptor("x", false, new java.util.function.Supplier<Boolean>() {
            public Boolean get() { throw new RuntimeException("boom"); }
        }, "test"));
        ToolDescriptor d = r.descriptor("bad");
        assertTrue(d.isAvailable());  // 第 1 次: 宽限
        expireProbeCache(d);
        assertFalse(d.isAvailable()); // 第 2 次: 判不可用
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
}
