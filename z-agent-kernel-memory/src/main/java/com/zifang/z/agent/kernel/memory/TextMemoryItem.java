package com.zifang.z.agent.kernel.memory;

import com.zifang.z.agent.kernel.message.Msg;

import java.util.Optional;

/**
 * 简单文本记忆条目. 用于 InMemoryMemoryStore.
 *
 * <p>getContent: 文本内容 (对话消息 / 摘要 / 事实).
 * <p>getEmbedding: 预留向量 (默认 null, 向量检索实现可填充).
 */
public interface TextMemoryItem extends MemoryItem {

    String getContent();

    default Optional<float[]> getEmbedding() {
        return Optional.empty();
    }
}