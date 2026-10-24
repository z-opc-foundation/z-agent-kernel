package com.zifang.z.agent.kernel.tts;

import org.junit.jupiter.api.Test;

import com.zifang.z.agent.kernel.tts.Tts.TtsOptions;
import com.zifang.z.agent.kernel.tts.Tts.TtsStream;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link FailoverTts} 契约测试.
 *
 * <p>核心是"**没吐音频才换**": TTS 已经播出半句时换厂商, 听众会听到两家声音拼在一起.
 * 一次性合成没有这个问题(失败就是失败), 所以它直接换.
 */
class FailoverTtsTest {

    /** 可编程的假 TTS. */
    private static final class StubTts implements Tts {
        final String id;
        RuntimeException synthesizeError;
        RuntimeException openStreamError;
        RuntimeException synthesizeStreamError;
        byte[] payload = new byte[]{1, 2, 3};
        int synthesizeCalls;
        int openStreamCalls;
        int synthesizeStreamCalls;
        String seenVoice;
        /** 异步模式: 不自动吐音频/不自动完成, 由测试自己触发回调. */
        boolean async;
        /** 这一家被 speak 到的文本(验证"换家后重放"). */
        final List<String> spoken = new ArrayList<String>();
        /** 异步模式下捕获到的回调. */
        Consumer<Throwable> capturedError;
        Consumer<byte[]> capturedChunk;
        Runnable capturedComplete;

        StubTts(String id) {
            this.id = id;
        }

        @Override
        public InputStream synthesize(String text, String voice, TtsOptions options) {
            synthesizeCalls++;
            seenVoice = voice;
            if (synthesizeError != null) {
                throw synthesizeError;
            }
            return new ByteArrayInputStream(payload);
        }

        @Override
        public String defaultVoice() {
            return id + "-voice";
        }

        @Override
        public TtsStream openStream(String voice, TtsOptions options,
                                     Consumer<byte[]> onChunk,
                                     Consumer<Throwable> onError,
                                     Runnable onComplete) {
            openStreamCalls++;
            capturedError = onError;
            capturedChunk = onChunk;
            capturedComplete = onComplete;
            if (openStreamError != null) {
                throw openStreamError;
            }
            if (!async) {
                onChunk.accept(payload);
                onComplete.run();
            }
            return new TtsStream() {
                @Override
                public void speak(String text) {
                    spoken.add(text);
                }

                @Override
                public void finish() {
                }

                @Override
                public void stop() {
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void synthesizeStream(String text, String voice, TtsOptions options,
                                     Consumer<byte[]> onChunk,
                                     Consumer<Throwable> onError,
                                     Runnable onComplete) {
            synthesizeStreamCalls++;
            capturedError = onError;
            capturedChunk = onChunk;
            capturedComplete = onComplete;
            if (synthesizeStreamError != null) {
                throw synthesizeStreamError;
            }
            if (!async) {
                onChunk.accept(payload);
                onComplete.run();
            }
        }
    }

    @Test
    void 一次性合成失败换下一家() {
        StubTts a = new StubTts("a");
        a.synthesizeError = new IllegalStateException("402 quota exhausted");
        StubTts b = new StubTts("b");

        FailoverTts chain = new FailoverTts(Arrays.asList(
                FailoverTts.entry("a", a, null),
                FailoverTts.entry("b", b, null)));

        InputStream in = chain.synthesize("你好", "v", TtsOptions.defaults());
        assertNotNull(in);
        assertEquals(1, a.synthesizeCalls);
        assertEquals(1, b.synthesizeCalls);
        assertEquals("v", b.seenVoice, "参数要原样传下去");
    }

    @Test
    void 不可用的实现被跳过() {
        StubTts a = new StubTts("a");
        StubTts b = new StubTts("b");
        FailoverTts chain = new FailoverTts(Arrays.asList(
                FailoverTts.entry("a", a, () -> false),
                FailoverTts.entry("b", b, null)));

        chain.synthesize("你好", "v", TtsOptions.defaults());
        assertEquals(0, a.synthesizeCalls);
        assertEquals(1, b.synthesizeCalls);
    }

    @Test
    void 全都不可用时给出可读异常() {
        StubTts a = new StubTts("a");
        a.synthesizeError = new IllegalStateException("down");
        FailoverTts chain = new FailoverTts(Arrays.asList(FailoverTts.entry("a", a, null)));

        try {
            chain.synthesize("你好", "v", TtsOptions.defaults());
            fail("应当抛异常");
        } catch (RuntimeException e) {
            assertEquals("down", e.getMessage(), "原始错误要透出来, 别吞");
        }
    }

    @Test
    void 流式建流失败换下一家() {
        StubTts a = new StubTts("a");
        a.openStreamError = new IllegalStateException("handshake failed");
        StubTts b = new StubTts("b");

        FailoverTts chain = new FailoverTts(Arrays.asList(
                FailoverTts.entry("a", a, null),
                FailoverTts.entry("b", b, null)));

        final List<byte[]> chunks = new ArrayList<byte[]>();
        final boolean[] done = {false};
        TtsStream stream = chain.openStream("v", TtsOptions.defaults(),
                chunk -> chunks.add(chunk), t -> {
                }, () -> done[0] = true);

        assertNotNull(stream);
        assertTrue(done[0]);
        assertEquals(1, chunks.size());
        assertEquals(1, a.openStreamCalls);
        assertEquals(1, b.openStreamCalls);
    }

    @Test
    void 合成流失败换下一家() {
        StubTts a = new StubTts("a");
        a.synthesizeStreamError = new IllegalStateException("429");
        StubTts b = new StubTts("b");

        FailoverTts chain = new FailoverTts(Arrays.asList(
                FailoverTts.entry("a", a, null),
                FailoverTts.entry("b", b, null)));

        final List<byte[]> chunks = new ArrayList<byte[]>();
        chain.synthesizeStream("你好", "v", TtsOptions.defaults(),
                chunk -> chunks.add(chunk), t -> {
                }, () -> {
                });

        assertEquals(1, a.synthesizeStreamCalls);
        assertEquals(1, b.synthesizeStreamCalls);
        assertEquals(1, chunks.size());
    }

    @Test
    void 流式还没出声就报错会换下一家并重放文本() {
        StubTts a = new StubTts("a");
        a.async = true;
        StubTts b = new StubTts("b");

        FailoverTts chain = new FailoverTts(Arrays.asList(
                FailoverTts.entry("a", a, null),
                FailoverTts.entry("b", b, null)));

        final List<byte[]> chunks = new ArrayList<byte[]>();
        final Throwable[] err = {null};
        TtsStream stream = chain.openStream("v", TtsOptions.defaults(),
                chunk -> chunks.add(chunk), t -> err[0] = t, () -> {
                });

        stream.speak("今天天气怎么样");
        assertEquals(1, a.openStreamCalls);
        assertEquals(Arrays.asList("今天天气怎么样"), a.spoken, "同一段文本不能被投递两次");

        // a 还没吐出任何音频就报错
        a.capturedError.accept(new IllegalStateException("ws closed"));

        assertEquals(1, b.openStreamCalls, "还没出声就失败 ⇒ 应当换下一家");
        assertEquals(Arrays.asList("今天天气怎么样"), b.spoken, "已经喂出去的文本必须重放给新的一家");
        assertNull(err[0], "换家成功就不该把错误抛给上层：" + err[0]);
    }

    @Test
    void 流式已经出声后报错不换下一家() {
        StubTts a = new StubTts("a");
        a.async = true;
        StubTts b = new StubTts("b");

        FailoverTts chain = new FailoverTts(Arrays.asList(
                FailoverTts.entry("a", a, null),
                FailoverTts.entry("b", b, null)));

        final List<byte[]> chunks = new ArrayList<byte[]>();
        final Throwable[] err = {null};
        TtsStream stream = chain.openStream("v", TtsOptions.defaults(),
                chunk -> chunks.add(chunk), t -> err[0] = t, () -> {
                });

        stream.speak("今天天气怎么样");
        a.capturedChunk.accept(new byte[]{7, 7});
        a.capturedError.accept(new IllegalStateException("connection reset"));

        assertEquals(0, b.openStreamCalls, "已经出声了就不能换, 否则两家的声音会拼在一起");
        assertNotNull(err[0], "出错必须抛给上层");
        assertEquals(1, chunks.size());
    }

    @Test
    void 合成流还没吐音频就报错会换下一家() {
        StubTts a = new StubTts("a");
        a.async = true;
        StubTts b = new StubTts("b");

        FailoverTts chain = new FailoverTts(Arrays.asList(
                FailoverTts.entry("a", a, null),
                FailoverTts.entry("b", b, null)));

        final List<byte[]> chunks = new ArrayList<byte[]>();
        final Throwable[] err = {null};
        chain.synthesizeStream("你好", "v", TtsOptions.defaults(),
                chunk -> chunks.add(chunk), t -> err[0] = t, () -> {
                });

        a.capturedError.accept(new IllegalStateException("429 rate limited"));

        assertEquals(1, b.synthesizeStreamCalls, "没吐音频就失败 ⇒ 换下一家");
        assertNull(err[0], "换家成功不该报错：" + err[0]);
    }

    @Test
    void 合成流已经吐过音频后报错不换下一家() {
        StubTts a = new StubTts("a");
        a.async = true;
        StubTts b = new StubTts("b");

        FailoverTts chain = new FailoverTts(Arrays.asList(
                FailoverTts.entry("a", a, null),
                FailoverTts.entry("b", b, null)));

        final List<byte[]> chunks = new ArrayList<byte[]>();
        final Throwable[] err = {null};
        chain.synthesizeStream("你好", "v", TtsOptions.defaults(),
                chunk -> chunks.add(chunk), t -> err[0] = t, () -> {
                });

        a.capturedChunk.accept(new byte[]{1});
        a.capturedError.accept(new IllegalStateException("boom"));

        assertEquals(0, b.synthesizeStreamCalls, "吐过音频就不能换");
        assertNotNull(err[0]);
    }

    @Test
    void 默认音色取第一个可用的() {
        StubTts a = new StubTts("a");
        StubTts b = new StubTts("b");
        FailoverTts chain = new FailoverTts(Arrays.asList(
                FailoverTts.entry("a", a, () -> false),
                FailoverTts.entry("b", b, null)));
        assertEquals("b-voice", chain.defaultVoice());
        assertEquals(Arrays.asList("a", "b"), chain.names());
    }

    @Test
    void 链上的音高是整型半音() {
        // 契约层就把 pitch 定成 int, 从类型上杜绝实现方把它序列化成浮点(MiniMax 会拒成 2013)
        TtsOptions options = TtsOptions.defaults();
        assertEquals(0, options.pitch);
        assertEquals(1.0f, options.speed);
        assertNotNull(options.audioFormat);
        assertEquals(24000, options.audioFormat.getSampleRate());
    }
}
