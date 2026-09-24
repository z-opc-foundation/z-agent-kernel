package com.zifang.z.agent.kernel.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版 SessionStore — 测试/无持久化场景的默认实现.
 * search 用朴素 contains; 生产实现 (SQLite+WAL+FTS5) 在 z-bot 仓.
 */
public final class InMemorySessionStore implements SessionStore {

    private final Map<String, SessionMeta> sessions = new LinkedHashMap<String, SessionMeta>();
    private final Map<String, List<StoredMessage>> messages = new LinkedHashMap<String, List<StoredMessage>>();
    private final Map<String, Long> touchSeq = new LinkedHashMap<String, Long>();
    private final AtomicLong seq = new AtomicLong();

    private void touch(String id) {
        touchSeq.put(id, seq.incrementAndGet());
    }

    @Override
    public synchronized String createSession(SessionMeta meta) {
        String id = meta.id == null || meta.id.isEmpty()
                ? "s_" + System.currentTimeMillis() + "_" + seq.incrementAndGet()
                : meta.id;
        meta.id = id;
        sessions.put(id, meta);
        messages.put(id, new ArrayList<StoredMessage>());
        touch(id);
        return id;
    }

    @Override
    public synchronized void appendMessage(String sessionId, StoredMessage message) {
        List<StoredMessage> list = messages.get(sessionId);
        if (list == null) {
            throw new IllegalArgumentException("no such session: " + sessionId);
        }
        message.createdAt = message.createdAt == 0 ? System.currentTimeMillis() : message.createdAt;
        list.add(message);
        SessionMeta meta = sessions.get(sessionId);
        if (meta != null) {
            meta.updatedAt = System.currentTimeMillis();
            touch(sessionId);
        }
    }

    @Override
    public synchronized List<StoredMessage> messages(String sessionId) {
        List<StoredMessage> list = messages.get(sessionId);
        return list == null ? Collections.<StoredMessage>emptyList()
                : new ArrayList<StoredMessage>(list);
    }

    @Override
    public synchronized boolean updateSession(SessionMeta meta) {
        SessionMeta cur = sessions.get(meta.id);
        if (cur == null) {
            return false;
        }
        cur.title = meta.title;
        cur.source = meta.source;
        cur.model = meta.model;
        cur.provider = meta.provider;
        cur.inputTokens = meta.inputTokens;
        cur.outputTokens = meta.outputTokens;
        cur.apiCalls = meta.apiCalls;
        cur.updatedAt = System.currentTimeMillis();
        touch(meta.id);
        return true;
    }

    @Override
    public synchronized SessionMeta session(String sessionId) {
        SessionMeta m = sessions.get(sessionId);
        return m == null ? null : m;
    }

    @Override
    public synchronized List<SessionMeta> listSessions(int limit) {
        List<SessionMeta> all = new ArrayList<SessionMeta>(sessions.values());
        Collections.sort(all, new Comparator<SessionMeta>() {
            public int compare(SessionMeta a, SessionMeta b) {
                int byTime = Long.compare(b.updatedAt, a.updatedAt);
                if (byTime != 0) {
                    return byTime;
                }
                // 同一毫秒: 后 touch 的排前 (单调序号 tie-break)
                return Long.compare(touchSeq.get(b.id), touchSeq.get(a.id));
            }
        });
        return all.size() <= limit ? all : new ArrayList<SessionMeta>(all.subList(0, limit));
    }

    @Override
    public synchronized List<SearchHit> search(String query, int limit) {
        List<SearchHit> out = new ArrayList<SearchHit>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, List<StoredMessage>> e : messages.entrySet()) {
            for (StoredMessage m : e.getValue()) {
                if (m.content != null && m.content.contains(query)) {
                    out.add(new SearchHit(e.getKey(), m));
                    if (out.size() >= limit) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    @Override
    public synchronized boolean deleteSession(String sessionId) {
        boolean had = sessions.remove(sessionId) != null;
        messages.remove(sessionId);
        touchSeq.remove(sessionId);
        return had;
    }

    @Override
    public void close() {
    }
}
