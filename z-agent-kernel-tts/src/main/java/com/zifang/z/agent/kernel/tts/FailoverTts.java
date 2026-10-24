package com.zifang.z.agent.kernel.tts;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 多厂商 TTS 组合 —— kernel 侧的**基建能力**: 把「MiniMax / 别的厂商」排成一条链,
 * 合成失败就换下一家.
 *
 * <p>为什么需要它: 各家 TTS 的**可用性条件不同**(要不要 key / 额度 / 模型名),
 * 而"今天哪把 key 能用"是运行期状态 ⇒ 不该让上层写 if/else.
 *
 * <p>切换规则:
 * <ul>
 *   <li>一次性 {@link #synthesize}: 失败直接换下一家重试(还没有内容产出, 可以重来);</li>
 *   <li>流式 {@link #openStream}: <b>还没吐出音频</b>时失败 ⇒ 换下一家重新开,
 *       并把已经喂进去的文本<b>重放</b>给新的一家(用户一个字的没听到, 重放没有副作用);
 *       <b>已经吐出音频</b>后失败 ⇒ 直接报错(换过去会把两家的声音拼在一起).</li>
 * </ul>
 *
 * <p>⚠️ 流式的切换是**异步**的(厂商的失败是回调通知的), 所以本类返回一个
 * **可换家的会话句柄**: 调用方始终拿着同一个 {@link TtsStream},
 * 内部在"还没出声"时悄悄换成下一家, 并把文本重放过去.
 */
public final class FailoverTts implements Tts {

    /** 链上的一个 TTS 实现(名字 + 实现 + 可用性判定). */
    public static final class Entry {
        private final String name;
        private final Tts tts;
        private final BooleanSupplier enabled;

        Entry(String name, Tts tts, BooleanSupplier enabled) {
            this.name = name;
            this.tts = tts;
            this.enabled = enabled;
        }

        public String name() {
            return name;
        }

        public Tts tts() {
            return tts;
        }

        public boolean enabled() {
            return enabled == null || enabled.getAsBoolean();
        }
    }

    private final List<Entry> entries;

    public FailoverTts(List<Entry> entries) {
        this.entries = entries == null ? Collections.<Entry>emptyList()
                : Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public FailoverTts(Tts... ttsList) {
        List<Entry> list = new ArrayList<Entry>();
        if (ttsList != null) {
            for (Tts t : ttsList) {
                if (t != null) {
                    list.add(entry(t.getClass().getSimpleName(), t, null));
                }
            }
        }
        this.entries = Collections.unmodifiableList(list);
    }

    public static Entry entry(String name, Tts tts, BooleanSupplier enabled) {
        return new Entry(name, tts, enabled);
    }

    public static Entry entry(String name, Tts tts) {
        return new Entry(name, tts, null);
    }

    public List<String> names() {
        List<String> out = new ArrayList<String>();
        for (Entry e : entries) {
            out.add(e.name);
        }
        return out;
    }

    @Override
    public String defaultVoice() {
        for (Entry e : entries) {
            if (e.enabled()) {
                return e.tts.defaultVoice();
            }
        }
        return entries.isEmpty() ? null : entries.get(0).tts.defaultVoice();
    }

    @Override
    public InputStream synthesize(String text, String voice, TtsOptions options) {
        RuntimeException last = null;
        for (Entry e : entries) {
            if (!e.enabled()) {
                continue;
            }
            try {
                return e.tts.synthesize(text, voice, options);
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        throw last != null ? last
                : new IllegalStateException("没有可用的 TTS 实现: " + names());
    }

    @Override
    public TtsStream openStream(String voice, TtsOptions options,
                                 Consumer<byte[]> onChunk,
                                 Consumer<Throwable> onError,
                                 Runnable onComplete) {
        return new SwitchableStream(voice, options, onChunk, onError, onComplete);
    }

    @Override
    public void synthesizeStream(String text, String voice, TtsOptions options,
                                 Consumer<byte[]> onChunk,
                                 Consumer<Throwable> onError,
                                 Runnable onComplete) {
        synthesizeStreamFrom(0, text, voice, options, onChunk, onError, onComplete);
    }

    /**
     * 一次性合成流 + 故障转移.
     *
     * <p>难点: 厂商的失败是**回调**通知的, 而"要不要换下一家"必须等回调来了才能判断.
     * 所以这里把 {@code onError} 先**截住**不往上抛, 由本方法决定:
     * 没吐过音频 ⇒ 换下一家重来; 吐过了 ⇒ 才把错误交给上层.
     */
    private void synthesizeStreamFrom(int from, String text, String voice, TtsOptions options,
                                      Consumer<byte[]> onChunk,
                                      Consumer<Throwable> onError,
                                      Runnable onComplete) {
        RuntimeException last = null;
        for (int i = from; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            if (!e.enabled()) {
                continue;
            }
            final int next = i + 1;
            final boolean[] produced = {false};
            final boolean[] settled = {false};
            try {
                e.tts.synthesizeStream(text, voice, options,
                        chunk -> {
                            produced[0] = true;
                            onChunk.accept(chunk);
                        },
                        err -> {
                            if (settled[0]) {
                                return;
                            }
                            settled[0] = true;
                            if (!produced[0]) {
                                // 还没吐音频 ⇒ 换下一家重来
                                synthesizeStreamFrom(next, text, voice, options, onChunk, onError, onComplete);
                            } else {
                                onError.accept(err);
                            }
                        },
                        () -> {
                            if (settled[0]) {
                                return;
                            }
                            settled[0] = true;
                            onComplete.run();
                        });
                return;
            } catch (RuntimeException ex) {
                if (settled[0]) {
                    // 错误已经处理过(要么转交上层, 要么已经换过家) ⇒ 不再重复处理
                    throw ex;
                }
                last = ex;
            }
        }
        throw last != null ? last
                : new IllegalStateException("没有可用的 TTS 实现: " + names());
    }

    /**
     * 可换家的增量会话.
     *
     * <p>调用方拿到的一直是这一个句柄; 内部在"还没出声"时换到下一家, 并把文本重放过去.
     */
    private final class SwitchableStream implements TtsStream {

        private final String voice;
        private final TtsOptions options;
        private final Consumer<byte[]> onChunk;
        private final Consumer<Throwable> onError;
        private final Runnable onComplete;

        /** 已经喂出去、但还没有成功播出音频的文本(换家时重放). */
        private final StringBuilder pending = new StringBuilder();

        private TtsStream current;
        /** 当前这家在链上的下标(换家时从它后面接着找). */
        private int currentIndex = -1;
        /** 当前这次尝试的序号(用来丢弃"过期 provider"的回调). */
        private int attempt;
        private boolean producedAudio;
        private boolean finishing;
        private boolean stopped;
        private boolean failed;
        /** 构造期(openStream 刚被调用)为 true —— 这期间"没有可用实现"应当抛异常而不是走回调. */
        private boolean constructing = true;

        SwitchableStream(String voice, TtsOptions options,
                          Consumer<byte[]> onChunk,
                          Consumer<Throwable> onError,
                          Runnable onComplete) {
            this.voice = voice;
            this.options = options;
            this.onChunk = onChunk;
            this.onError = onError;
            this.onComplete = onComplete;
            // 立刻打开第一家: 与 Tts.openStream 的语义一致(连接错误早暴露), 也让"建流失败就换下一家"成立
            openFrom(0);
            constructing = false;
        }

        @Override
        public void speak(String text) {
            if (stopped || failed || text == null || text.isEmpty()) {
                return;
            }
            // ⚠️ 顺序要紧: 先开流(开流会把**已有**的 pending 重放一遍), 再把新文本追加进 pending.
            // 反过来写会把这段文本发两遍(一次在开流的重放里, 一次在这里).
            ensureOpen();
            pending.append(text);
            if (current != null) {
                try {
                    current.speak(text);
                } catch (RuntimeException e) {
                    onProviderFailure(e);
                }
            }
        }

        @Override
        public void finish() {
            if (stopped || failed) {
                return;
            }
            finishing = true;
            ensureOpen();
            if (current != null) {
                try {
                    current.finish();
                } catch (RuntimeException e) {
                    onProviderFailure(e);
                }
            }
        }

        @Override
        public void stop() {
            stopped = true;
            pending.setLength(0);
            if (current != null) {
                try {
                    current.stop();
                } catch (RuntimeException ignored) {
                    // 已经掐断了, 关闭时的异常不用上抛
                }
            }
        }

        @Override
        public void close() {
            stopped = true;
            pending.setLength(0);
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException ignored) {
                    // 同上
                }
            }
        }

        /** 确保已经有一家在听(没有就开第一家). */
        private void ensureOpen() {
            if (current == null && !failed) {
                openFrom(0);
            }
        }

        /**
         * 从第 from 个条目开始找一家可用并打开; 打开成功后把 pending 的文本重放给它.
         *
         * @return 成功打开返回 true; 全都不行则置 failed 并回调 onError
         */
        private boolean openFrom(int from) {
            RuntimeException last = null;
            for (int i = from; i < entries.size(); i++) {
                final Entry e = entries.get(i);
                if (!e.enabled()) {
                    continue;
                }
                final int myAttempt = ++attempt;
                final int next = i + 1;
                final boolean[] gotAudio = {false};
                try {
                    TtsStream stream = e.tts.openStream(voice, options,
                            chunk -> {
                                if (myAttempt != attempt) {
                                    return;
                                }
                                gotAudio[0] = true;
                                producedAudio = true;
                                onChunk.accept(chunk);
                            },
                            err -> {
                                if (myAttempt != attempt) {
                                    return;
                                }
                                if (!gotAudio[0] && !producedAudio) {
                                    // 还没出声 ⇒ 换下一家, 并把文本重放过去
                                    closeQuietly(current);
                                    current = null;
                                    openFrom(next);
                                } else {
                                    onError.accept(err);
                                }
                            },
                            () -> {
                                if (myAttempt == attempt) {
                                    onComplete.run();
                                }
                            });
                    current = stream;
                    currentIndex = i;
                    if (pending.length() > 0) {
                        stream.speak(pending.toString());
                    }
                    if (finishing) {
                        stream.finish();
                    }
                    return true;
                } catch (RuntimeException ex) {
                    last = ex;
                    current = null;
                }
            }
            failed = true;
            if (constructing) {
                // 构造期没有一家可用 ⇒ 与旧行为一致: 直接抛
                throw new IllegalStateException("没有可用的 TTS 实现: " + names(), last);
            }
            onError.accept(last != null ? last
                    : new IllegalStateException("没有可用的 TTS 实现: " + names()));
            return false;
        }

        /** 当前 provider 抛异常时的处置: 没出声就换下一家, 出过声就报错. */
        private void onProviderFailure(RuntimeException ex) {
            if (!producedAudio) {
                closeQuietly(current);
                current = null;
                openFrom(currentIndex + 1);
            } else {
                onError.accept(ex);
            }
        }

        private void closeQuietly(TtsStream stream) {
            if (stream == null) {
                return;
            }
            try {
                stream.close();
            } catch (RuntimeException ignored) {
                // 关掉一家已经挂掉的实现, 异常不用上抛
            }
        }
    }
}
