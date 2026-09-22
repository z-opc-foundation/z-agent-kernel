package com.zifang.z.agent.kernel.llm;

import java.util.List;

/**
 * LLM 模型元数据. 描述一个可用模型的 provider / 类型 / 能力.
 *
 * <p>capabilities: 支持的能力位 (CHAT / STREAM / TOOLS / VISION 等).
 */
public final class Model {

    private final String id;
    private final String displayName;
    private final String provider;
    private final List<Capability> capabilities;
    private final long contextWindow;
    private final long maxOutputTokens;

    public Model(String id, String displayName, String provider,
                 List<Capability> capabilities, long contextWindow, long maxOutputTokens) {
        this.id = id;
        this.displayName = displayName;
        this.provider = provider;
        this.capabilities = capabilities == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(capabilities);
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getProvider() {
        return provider;
    }

    public List<Capability> getCapabilities() {
        return capabilities;
    }

    public long getContextWindow() {
        return contextWindow;
    }

    public long getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public enum Capability {
        CHAT,
        STREAM,
        TOOLS,
        VISION,
        AUDIO,
        JSON_MODE
    }
}