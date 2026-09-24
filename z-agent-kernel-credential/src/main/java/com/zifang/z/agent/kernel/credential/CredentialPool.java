package com.zifang.z.agent.kernel.credential;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 凭据池 — 同一 provider 多把 key 轮换, 带失败计数与冷却 (对齐 hermes credential_pool.py).
 *
 * <p>pick(): 按顺序挑第一把"健康"的 key (失败数清零或冷却已过);
 * 全部都在冷却 → 返回剩余冷却最短的一把 (调用方仍可尝试, 撞限速总比拒绝好).
 * reportFailure(key, cooldownMs): 失败数 +1, 进入冷却 (如 429 的 Retry-After);
 * reportSuccess(key): 失败数清零.
 */
public final class CredentialPool {

    private static final class Entry {
        final String key;
        volatile int failures;
        volatile long cooldownUntil;

        Entry(String key) {
            this.key = key;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<String, Entry>();
    private final List<String> order;

    public CredentialPool(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("credential pool needs at least one key");
        }
        this.order = java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(keys));
        for (String k : keys) {
            entries.put(k, new Entry(k));
        }
    }

    /** @return 下一把应使用的 key. */
    public synchronized String pick() {
        long now = System.currentTimeMillis();
        Entry best = null;
        long bestReadyAt = Long.MAX_VALUE;
        for (String k : order) {
            Entry e = entries.get(k);
            if (e.cooldownUntil <= now) {
                return k; // 第一把健康的 (顺序轮换语义)
            }
            if (e.cooldownUntil < bestReadyAt) {
                bestReadyAt = e.cooldownUntil;
                best = e;
            }
        }
        return best.key; // 全在冷却: 剩余冷却最短的
    }

    public void reportFailure(String key, long cooldownMs) {
        Entry e = entries.get(key);
        if (e != null) {
            e.failures++;
            e.cooldownUntil = System.currentTimeMillis() + Math.max(0, cooldownMs);
        }
    }

    public void reportSuccess(String key) {
        Entry e = entries.get(key);
        if (e != null) {
            e.failures = 0;
            e.cooldownUntil = 0;
        }
    }

    public int failures(String key) {
        Entry e = entries.get(key);
        return e == null ? 0 : e.failures;
    }

    /** @return 指定 key 是否仍在冷却中. */
    public boolean coolingDown(String key) {
        Entry e = entries.get(key);
        return e != null && e.cooldownUntil > System.currentTimeMillis();
    }

    public int size() {
        return order.size();
    }
}
