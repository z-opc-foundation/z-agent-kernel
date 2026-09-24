package com.zifang.z.agent.kernel.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Toolset 分发 — 不同入口面 (cli / gateway / 子代理 / IM 频道) 暴露不同的工具子集.
 *
 * <p>语义对齐 hermes toolsets.py + toolset_distributions.py: 工具按 toolset 分组,
 * 入口面声明允许的 toolset 白名单, 再叠加 per-tool 禁用名单得到最终暴露集合.
 */
public final class ToolsetDistributions {

    private final ToolRegistry registry;
    private final Set<String> allowedToolsets;

    public ToolsetDistributions(ToolRegistry registry, Set<String> allowedToolsets) {
        this.registry = registry;
        this.allowedToolsets = allowedToolsets == null ? null
                : java.util.Collections.unmodifiableSet(allowedToolsets);
    }

    /** @return 该入口面最终暴露的工具: toolset 白名单 ∩ 可用探测 ∩ 非禁用名单. */
    public List<Tool> exposedTools(Set<String> disabledTools) {
        List<Tool> out = new ArrayList<Tool>();
        for (Tool t : registry.availableTools()) {
            ToolDescriptor d = registry.descriptor(t.getName());
            if (allowedToolsets != null && !allowedToolsets.contains(d.getToolset())) {
                continue;
            }
            if (disabledTools != null && disabledTools.contains(t.getName())) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /** @return 工具名是否在该入口面可见 (含探测). */
    public boolean isExposed(String toolName, Set<String> disabledTools) {
        if (!registry.has(toolName)) {
            return false;
        }
        if (disabledTools != null && disabledTools.contains(toolName)) {
            return false;
        }
        if (!registry.descriptor(toolName).isAvailable()) {
            return false;
        }
        return allowedToolsets == null || allowedToolsets.contains(registry.descriptor(toolName).getToolset());
    }

    /** @return 白名单内 toolset → 工具名 快照. */
    public Map<String, List<String>> catalog() {
        Map<String, List<String>> all = registry.snapshotByToolset();
        if (allowedToolsets == null) {
            return all;
        }
        java.util.LinkedHashMap<String, List<String>> out = new java.util.LinkedHashMap<String, List<String>>();
        for (String ts : allowedToolsets) {
            List<String> names = all.get(ts);
            if (names != null) {
                out.put(ts, names);
            }
        }
        return out;
    }
}
