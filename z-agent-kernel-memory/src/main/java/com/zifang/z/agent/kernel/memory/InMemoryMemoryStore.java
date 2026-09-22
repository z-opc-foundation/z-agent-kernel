package com.zifang.z.agent.kernel.memory;

import com.zifang.z.agent.kernel.message.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存版记忆存储 — 默认实现, 用 ConcurrentHashMap 存条目.
 *
 * <p>scope: 通常是 user id / session id / agent id, 由调用方在构造时指定.
 * <p>search: 子串匹配(简单实现); 向量检索可由 VectorMemoryStore 替代.
 *
 * <p>对应 z-opc 老 InMemoryMemory (distilled).
 */
public class InMemoryMemoryStore implements MemoryStore<TextMemoryItem> {

    private final String scope;
    private final ConcurrentMap<String, TextMemoryItem> store = new ConcurrentHashMap<String, TextMemoryItem>();

    public InMemoryMemoryStore(String scope) {
        if (scope == null || scope.isEmpty()) {
            throw new IllegalArgumentException("scope required");
        }
        this.scope = scope;
    }

    @Override
    public String getScope() {
        return scope;
    }

    @Override
    public void save(TextMemoryItem item) {
        if (item == null || item.getId() == null) {
            throw new IllegalArgumentException("item and id required");
        }
        store.put(item.getId(), item);
    }

    @Override
    public Optional<TextMemoryItem> load(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<TextMemoryItem> search(String query, int limit) {
        if (query == null || query.isEmpty()) {
            return new ArrayList<TextMemoryItem>(store.values());
        }
        String needle = query.toLowerCase();
        List<TextMemoryItem> hits = new ArrayList<TextMemoryItem>();
        for (TextMemoryItem item : store.values()) {
            if (item.getContent() != null && item.getContent().toLowerCase().contains(needle)) {
                hits.add(item);
                if (limit > 0 && hits.size() >= limit) break;
            }
        }
        return hits;
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }

    @Override
    public List<Msg> listRecentMessages(int limit) {
        List<TextMemoryItem> all = new ArrayList<TextMemoryItem>(store.values());
        if (all.size() > limit) {
            all = all.subList(all.size() - limit, all.size());
        }
        List<Msg> msgs = new ArrayList<Msg>(all.size());
        for (TextMemoryItem item : all) {
            String content = item.getContent() == null ? "" : item.getContent();
            msgs.add(new Msg(com.zifang.z.agent.kernel.types.MessageRole.USER, content));
        }
        return msgs;
    }

    public int size() {
        return store.size();
    }

    public void clear() {
        store.clear();
    }
}