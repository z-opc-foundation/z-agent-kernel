package com.zifang.z.agent.kernel.state;

import java.util.List;

/**
 * 会话持久化 SPI — agent 会话与消息的结构化存储 (对齐 hermes state.db).
 *
 * <p>实现方决定存储介质 (SQLite / 内存 / JSON 文件). kernel 只定义契约:
 * 会话元数据 + 消息追加 + 全文检索 + 导出 + 清理. 实现必须线程安全,
 * 且支持多进程并发写时读侧不破坏 (SQLite → WAL).
 */
public interface SessionStore {

    /** 新建会话, 返回会话 id (实现方保证唯一). */
    String createSession(SessionMeta meta);

    /** 追加一条消息 (order 由实现方自增维护). */
    void appendMessage(String sessionId, StoredMessage message);

    /** @return 会话内全部消息 (按追加顺序). */
    List<StoredMessage> messages(String sessionId);

    /** 更新会话元数据 (标题 / 计数 / 模型等), 不存在则 no-op 返回 false. */
    boolean updateSession(SessionMeta meta);

    /** @return 会话元数据; 不存在返回 null. */
    SessionMeta session(String sessionId);

    /** @return 会话列表 (按更新时间倒序). */
    List<SessionMeta> listSessions(int limit);

    /** 全文检索消息; 实现方无全文索引时退化为 LIKE. @return 命中的 (会话id, 消息) 对. */
    List<SearchHit> search(String query, int limit);

    /** 删除会话及其消息. @return 是否删了东西. */
    boolean deleteSession(String sessionId);

    /** 关闭底层资源 (连接池 / 文件句柄). */
    void close();

    /** 会话元数据. */
    final class SessionMeta {
        public String id;
        public String title;
        public String source;
        public String model;
        public String provider;
        public long inputTokens;
        public long outputTokens;
        public int apiCalls;
        public long createdAt;
        public long updatedAt;

        public SessionMeta() {
        }

        public SessionMeta(String id, String title, String source, String model, String provider) {
            this.id = id;
            this.title = title;
            this.source = source;
            this.model = model;
            this.provider = provider;
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = this.createdAt;
        }
    }

    /** 落库的一条消息. */
    final class StoredMessage {
        public String role;
        public String content;
        /** JSON 数组文本: assistant 的 tool_calls (原样透传). */
        public String toolCallsJson;
        public String toolCallId;
        public String reasoning;
        public long createdAt;

        public StoredMessage() {
        }

        public StoredMessage(String role, String content) {
            this.role = role;
            this.content = content;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /** 检索命中. */
    final class SearchHit {
        public final String sessionId;
        public final StoredMessage message;

        public SearchHit(String sessionId, StoredMessage message) {
            this.sessionId = sessionId;
            this.message = message;
        }
    }
}
