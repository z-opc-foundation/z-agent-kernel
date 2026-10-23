package com.zifang.z.agent.kernel.llm;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM provider SPI. kernel.llm 体系的核心接口, 各 provider (OpenAI / Anthropic / DeepSeek / ...)
 * 都实现此接口.
 *
 * <p>name: provider 名 ("openai" / "anthropic" / "deepseek" / ...).
 * <p>listModels: 列出 provider 支持的所有模型.
 * <p>chat: 同步 chat completion.
 * <p>streamChat: 流式 chat completion, 每个 chunk 回调 onChunk.
 * <p>supportsModel: 判断某个 model id 是否归本 provider.
 */
public interface LlmProvider {

    String name();

    List<Model> listModels();

    boolean supportsModel(String modelId);

    ChatCompletionsResponse chat(ChatCompletionsRequest request);

    void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk, Consumer<Throwable> onError);

    /**
     * 带结束信号的流式 chat —— <b>流式实现默认是异步的</b>（SSE 客户端在另一个线程上吐 chunk），
     * 所以调用方不能凭"方法返回了"判断流结束：必须等 {@code onComplete} 或 {@code onError}。
     *
     * <p>三参版对同步实现仍然正确（方法返回即流已结束），故默认实现直接转调并立刻收尾。
     * 走 SSE 的 provider 必须覆写本方法，把结束信号透传给底层 HTTP 客户端，
     * 否则上层会在零 chunk 时就宣布成功并关闭客户端流（真实故障：网关对 MiniMax
     * 的流式只回 {@code data: [DONE]}，同步却完全正常）。
     */
    default void streamChat(ChatCompletionsRequest request,
                             Consumer<ChatCompletionsResponse> onChunk,
                             Consumer<Throwable> onError,
                             Runnable onComplete) {
        streamChat(request, onChunk, onError);
        onComplete.run();
    }

    default Map<String, Object> providerParams() {
        return java.util.Collections.emptyMap();
    }
}