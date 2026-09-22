package com.zifang.z.agent.kernel.rag;

import java.util.List;
import java.util.Map;

/**
 * RAG Pipeline SPI — 检索增强生成主流程.
 *
 * <p>实现方: z-kb.
 *
 * <p>职责: 接 query → 检索 topK 文档 → (可选)重排 → 拼成 prompt context 返回给 agent.
 */
public interface RagPipeline {

    /**
     * @param query 用户查询
     * @param topK  召回条数(默认 5)
     * @return 检索结果, 按相关度降序
     */
    List<Document> retrieve(String query, int topK);

    /**
     * 把检索结果拼成 LLM 友好的 prompt 片段(包含来源标记, 便于引用).
     */
    String buildContext(List<Document> documents);

    /**
     * 检索到的文档片段.
     */
    final class Document {
        public final String id;
        public final String content;
        public final double score;
        public final Map<String, Object> metadata;

        public Document(String id, String content, double score, Map<String, Object> metadata) {
            this.id = id;
            this.content = content;
            this.score = score;
            this.metadata = metadata == null ? java.util.Collections.emptyMap() : metadata;
        }
    }
}