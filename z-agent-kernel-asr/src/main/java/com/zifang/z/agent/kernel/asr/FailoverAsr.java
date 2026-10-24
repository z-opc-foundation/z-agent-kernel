package com.zifang.z.agent.kernel.asr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.zifang.z.agent.kernel.types.AudioFormat;

/**
 * 多厂商 ASR 组合 —— kernel 侧的**基建能力**: 把「百炼流式 / MiniMax 整段 / MiMo 整段」
 * 排成一条链, 按可用性挑一个来用.
 *
 * <p>为什么需要它: 各家 ASR 的**可用性条件不同**(要不要 key、额度有没有、模型名对不对),
 * 而"用户今天有哪把 key"是**运行期状态** ⇒ 不该让上层业务代码写 if/else.
 *
 * <p>挑选规则: 链上第一个 {@link Entry#enabled()} 为真的 provider 胜出;
 * {@code open()} 抛异常就顺延到下一个.
 *
 * <p>用法:
 * <pre>
 * FailoverAsr asr = new FailoverAsr(
 *     FailoverAsr.entry("dashscope", dashScopeAsr, () -&gt; keyConfigured),
 *     FailoverAsr.entry("minimax",   miniMaxAsr,   () -&gt; keyConfigured));
 * </pre>
 *
 * <p>⚠️ 本组合只负责**选谁**, 不做"谁先出结果就用谁"的竞速 —— ASR 是有状态的
 * (音频要连续喂给同一家), 换厂商意味着放弃已经积累的上下文.
 */
public final class FailoverAsr implements Asr {

    /** 链上的一个 ASR 实现(名字 + 实现 + 可用性判定). */
    public static final class Entry {
        private final String name;
        private final Asr asr;
        private final BooleanSupplier enabled;

        Entry(String name, Asr asr, BooleanSupplier enabled) {
            this.name = name;
            this.asr = asr;
            this.enabled = enabled;
        }

        public String name() {
            return name;
        }

        public Asr asr() {
            return asr;
        }

        /** 当前是否可用(默认 true). */
        public boolean enabled() {
            return enabled == null || enabled.getAsBoolean();
        }
    }

    private final List<Entry> entries;
    private volatile Entry active;

    public FailoverAsr(List<Entry> entries) {
        this.entries = entries == null ? Collections.<Entry>emptyList()
                : Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public FailoverAsr(Asr... asrs) {
        List<Entry> list = new ArrayList<Entry>();
        if (asrs != null) {
            for (Asr a : asrs) {
                if (a != null) {
                    list.add(entry(a.getClass().getSimpleName(), a, null));
                }
            }
        }
        this.entries = Collections.unmodifiableList(list);
    }

    public static Entry entry(String name, Asr asr, BooleanSupplier enabled) {
        return new Entry(name, asr, enabled);
    }

    public static Entry entry(String name, Asr asr) {
        return new Entry(name, asr, null);
    }

    /** 链上各 provider 的名字(诊断用). */
    public List<String> names() {
        List<String> out = new ArrayList<String>();
        for (Entry e : entries) {
            out.add(e.name);
        }
        return out;
    }

    /** 当前实际生效的 provider 名字(还没 open 过则为 null). */
    public String activeName() {
        Entry e = active;
        return e == null ? null : e.name;
    }

    @Override
    public AudioFormat inputFormat() {
        for (Entry e : entries) {
            if (e.enabled()) {
                return e.asr.inputFormat();
            }
        }
        return entries.isEmpty() ? null : entries.get(0).asr.inputFormat();
    }

    @Override
    public AsrSession open(Consumer<AsrEvent> onEvent, Consumer<Throwable> onError) {
        RuntimeException last = null;
        for (Entry e : entries) {
            if (!e.enabled()) {
                continue;
            }
            try {
                AsrSession session = e.asr.open(onEvent, onError);
                active = e;
                return session;
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        throw last != null ? last
                : new IllegalStateException("没有可用的 ASR 实现: " + names());
    }

    @Override
    public void close(AsrSession session) {
        if (session == null) {
            return;
        }
        // 优先关"真正开出这个会话的那一家" —— 实现方的 close 未必会为别人的 session 抛异常
        Entry owner = active;
        if (owner != null) {
            try {
                owner.asr.close(session);
                return;
            } catch (RuntimeException ignored) {
                // 不是这一家的 session, 继续找
            }
        }
        for (Entry e : entries) {
            try {
                e.asr.close(session);
                return;
            } catch (RuntimeException ignored) {
                // 不是这一家的 session, 继续找
            }
        }
    }
}
