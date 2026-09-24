package com.zifang.z.agent.kernel.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 — 一切工具能力的插槽机制 (对齐 hermes tools/registry.py).
 *
 * <p>特性: 自注册 (register 而非硬编码清单) / owner 所有权校验 (覆写与注销须同 owner,
 * 插件不得顶掉内建工具) / generation 计数器 (schema 缓存失效信号) / 按 toolset 查询 /
 * 可用性探测过滤.
 */
public final class ToolRegistry {

    /** 注册表当前代数 — 工具集发生变化时递增, 上层据此失效缓存的 schema 列表. */
    private final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong();

    private final Map<String, RegisteredTool> tools =
            new java.util.concurrent.ConcurrentHashMap<String, RegisteredTool>();

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

    public Tool get(String name) {
        RegisteredTool rt = tools.get(name);
        return rt == null ? null : rt.tool;
    }

    public ToolDescriptor descriptor(String name) {
        RegisteredTool rt = tools.get(name);
        return rt == null ? null : rt.descriptor;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** @return 全部工具名 (注册顺序). */
    public List<String> names() {
        return new ArrayList<String>(tools.keySet());
    }

    /** @return 指定 toolset 的全部工具 (注册顺序). */
    public List<Tool> byToolset(String toolset) {
        List<Tool> out = new ArrayList<Tool>();
        for (RegisteredTool rt : tools.values()) {
            if (rt.descriptor.getToolset().equals(toolset)) {
                out.add(rt.tool);
            }
        }
        return out;
    }

    /** @return 当前可用 (探测通过) 的全部工具. */
    public List<Tool> availableTools() {
        List<Tool> out = new ArrayList<Tool>();
        for (RegisteredTool rt : tools.values()) {
            if (rt.descriptor.isAvailable()) {
                out.add(rt.tool);
            }
        }
        return out;
    }

    /** @return toolset → 工具名列表 快照 (调试/分发展示用). */
    public Map<String, List<String>> snapshotByToolset() {
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

    public int size() {
        return tools.size();
    }
}
