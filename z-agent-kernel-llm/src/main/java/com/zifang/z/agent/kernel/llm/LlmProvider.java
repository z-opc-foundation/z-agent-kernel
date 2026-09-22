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

    default Map<String, Object> providerParams() {
        return java.util.Collections.emptyMap();
    }
}