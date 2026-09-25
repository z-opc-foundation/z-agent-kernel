package com.zifang.z.agent.kernel.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 — 一切工具能力的插槽机制 (对齐 hermes tools/registry.py).
 *
 * <p>特性: 自注册 (register 而非硬编码清单) / owner 所有权校验 (覆写与注销须同 owner,
 * 插件不得顶掉内建工具) / generation 计数器 (schema 缓存失效信号) / 按 toolset 查询与
 * **按 toolset 整体注销** (MCP dynamic discovery 的 nuk-and-repave) / 可用性探测过滤 /
 * 单工具结果上限查询 (对齐 registry.py:650 get_max_result_size).
 *
 * <p>注册顺序即遍历顺序 (LinkedHashMap, 全部读写在同一把锁内), 保证 schema 前缀逐字节可重放
 * —— 顺序抖动会直接打掉 provider 侧 prompt cache.
 */
public final class ToolRegistry {

    /** 未声明结果上限时的全局缺省 (对齐 hermes budget_config.DEFAULT_RESULT_SIZE_CHARS=100_000). */
    public static final long DEFAULT_RESULT_SIZE_CHARS = 100_000L;

    /** 注册表当前代数 — 工具集发生变化时递增, 上层据此失效缓存的 schema 列表. */
    private final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong();

    private final Map<String, RegisteredTool> tools =
            new LinkedHashMap<String, RegisteredTool>();

    /** 注册表里的一个槽位: 工具 + 描述 + 注册它的 owner. */
    private static final class RegisteredTool {
        final Tool tool;
        final ToolDescriptor descriptor;

        RegisteredTool(Tool tool, ToolDescriptor descriptor) {
            this.tool = tool;
            this.descriptor = descriptor;
        }
    }

    /** 注册工具 (owner=kernel, 不可用性探测无, 非 parallelSafe). */
    public void register(Tool tool, String toolset) {
        register(tool, new ToolDescriptor(toolset, false, null, "kernel"));
    }

    /** 注册工具, 带完整描述与 owner. 同名工具: 未注册过 → 注册; 同 owner → 覆写; 异 owner → 拒绝. */
    public synchronized void register(Tool tool, ToolDescriptor descriptor) {
        RegisteredTool prev = tools.get(tool.getName());
        if (prev != null && !prev.descriptor.getOwner().equals(descriptor.getOwner())) {
            throw new IllegalStateException("tool '" + tool.getName() + "' owned by '"
                    + prev.descriptor.getOwner() + "', '" + descriptor.getOwner() + "' 不能覆写");
        }
        tools.put(tool.getName(), new RegisteredTool(tool, descriptor));
        generation.incrementAndGet();
    }

    /** 注销 — 仅 owner 本人可注销自己的工具. */
    public synchronized boolean deregister(String toolName, String owner) {
        RegisteredTool rt = tools.get(toolName);
        if (rt == null) {
            return false;
        }
        if (!rt.descriptor.getOwner().equals(owner)) {
            throw new IllegalStateException("tool '" + toolName + "' owned by '"
                    + rt.descriptor.getOwner() + "', '" + owner + "' 不能注销");
        }
        tools.remove(toolName);
        generation.incrementAndGet();
        return true;
    }

    /**
     * 按 toolset 整体注销 — 对齐 hermes registry.deregister 的 MCP 用法
     * ("nuke-and-repave when a server sends notifications/tools/list_changed", registry.py:463).
     *
     * <p>比"记住上次注册的名字再逐个删"更硬: server 侧改了工具名/清单时, 老名字同样被清掉,
     * 不会残留在表里占 schema 名额.
     *
     * @return 实际被注销的工具名 (注册顺序)
     */
    public synchronized List<String> deregisterByToolset(String toolset, String owner) {
        List<String> removed = new ArrayList<String>();
        Iterator<Map.Entry<String, RegisteredTool>> it = tools.entrySet().iterator();
        while (it.hasNext()) {
            RegisteredTool rt = it.next().getValue();
            if (!rt.descriptor.getToolset().equals(toolset)) {
                continue;
            }
            if (owner != null && !rt.descriptor.getOwner().equals(owner)) {
                throw new IllegalStateException("tool '" + rt.tool.getName() + "' owned by '"
                        + rt.descriptor.getOwner() + "', '" + owner + "' 不能注销 toolset '" + toolset + "'");
            }
            it.remove();
            removed.add(rt.tool.getName());
            generation.incrementAndGet();
        }
        return removed;
    }

    public synchronized Tool get(String name) {
        RegisteredTool rt = tools.get(name);
        return rt == null ? null : rt.tool;
    }

    public synchronized ToolDescriptor descriptor(String name) {
        RegisteredTool rt = tools.get(name);
        return rt == null ? null : rt.descriptor;
    }

    public synchronized boolean has(String name) {
        return tools.containsKey(name);
    }

    /** @return 全部工具名 (注册顺序). */
    public synchronized List<String> names() {
        return new ArrayList<String>(tools.keySet());
    }

    /** @return 指定 toolset 的全部工具 (注册顺序). */
    public synchronized List<Tool> byToolset(String toolset) {
        List<Tool> out = new ArrayList<Tool>();
        for (RegisteredTool rt : tools.values()) {
            if (rt.descriptor.getToolset().equals(toolset)) {
                out.add(rt.tool);
            }
        }
        return out;
    }

    /** @return 当前可用 (探测通过) 的全部工具 (注册顺序). */
    public synchronized List<Tool> availableTools() {
        List<Tool> out = new ArrayList<Tool>();
        for (RegisteredTool rt : tools.values()) {
            if (rt.descriptor.isAvailable()) {
                out.add(rt.tool);
            }
        }
        return out;
    }

    /** @return 当前不可用 (探测失败且已过宽限窗) 的工具名 — 只从 schema 里消失, 槽位仍在. */
    public synchronized List<String> unavailableNames() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, RegisteredTool> e : tools.entrySet()) {
            if (!e.getValue().descriptor.isAvailable()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * 单工具结果字符上限 — 对齐 hermes registry.get_max_result_size(name, default).
     * 未声明 (或工具不存在) 走 {@code defaultChars}; 声明为
     * {@link ToolDescriptor#UNBOUNDED_RESULT_CHARS} 的工具表示刻意不设限.
     */
    public long maxResultChars(String toolName, long defaultChars) {
        ToolDescriptor d = descriptor(toolName);
        if (d == null || d.getMaxResultChars() == ToolDescriptor.NO_MAX_RESULT_CHARS) {
            return defaultChars;
        }
        return d.getMaxResultChars();
    }

    /** @return toolset → 工具名列表 快照 (调试/分发展示用). */
    public synchronized Map<String, List<String>> snapshotByToolset() {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, RegisteredTool> e : tools.entrySet()) {
            String ts = e.getValue().descriptor.getToolset();
            List<String> list = out.get(ts);
            if (list == null) {
                list = new ArrayList<String>();
                out.put(ts, list);
            }
            list.add(e.getKey());
        }
        for (List<String> l : out.values()) {
            Collections.sort(l);
        }
        return out;
    }

    /** @return 当前代数 — 工具集变化时递增; 调用方可缓存 schema 列表并在代数变化时重建. */
    public long generation() {
        return generation.get();
    }

    public synchronized int size() {
        return tools.size();
    }
}
