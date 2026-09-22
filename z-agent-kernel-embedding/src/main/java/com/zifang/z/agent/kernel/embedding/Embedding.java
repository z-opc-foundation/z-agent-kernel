package com.zifang.z.agent.kernel.embedding;

import java.util.List;

/**
 * Embedding SPI — 向量化接口. 把文本转成稠密向量, 用于语义检索 / RAG.
 *
 * <p>实现方: z-vector.
 *
 * <p>调用方: kernel.rag.RagPipeline (相似度检索) / kernel.memory.VectorMemoryStore (向量记忆).
 */
public interface Embedding {

    /**
     * 单条文本向量化.
     *
     * @param text 输入文本(UTF-8, 通常 ≤ 8K 字符)
     * @return 浮点向量(长度 = {@link #dim()})
     */
    float[] embed(String text);

    /**
     * 批量向量化(并行加速).
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * @return 模型输出的向量维度(如 1536 / 3072 / 1024)
     */
    int dim();

    /**
     * @return 模型标识(如 "text-embedding-3-small" / "bge-m3")
     */
    String model();
}