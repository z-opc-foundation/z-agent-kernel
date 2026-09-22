package com.zifang.z.agent.kernel.memory;

/**
 * 记忆条目接口. kernel.memory 内部统一条目抽象.
 *
 * <p>getId: 唯一 id.
 * <p>getScore: 检索相似度 (0-1, 默认 1.0 for 精确匹配).
 */
public interface MemoryItem {

    String getId();

    double getScore();
}