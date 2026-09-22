package com.zifang.z.agent.kernel.types;

/**
 * 共享 POJO: LLM token 用量统计. kernel.llm.ChatCompletionsResponse 内嵌此对象, 也供 observability 层使用.
 */
public final class TokenUsage {

    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;

    public TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public static TokenUsage empty() {
        return new TokenUsage(0L, 0L, 0L);
    }

    @Override
    public String toString() {
        return "TokenUsage{prompt=" + promptTokens + ", completion=" + completionTokens + ", total=" + totalTokens + '}';
    }
}