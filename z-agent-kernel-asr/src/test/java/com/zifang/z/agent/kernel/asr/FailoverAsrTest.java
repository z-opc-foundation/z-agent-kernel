package com.zifang.z.agent.kernel.asr;

import org.junit.jupiter.api.Test;

import com.zifang.z.agent.kernel.types.AudioFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link FailoverAsr} 契约测试.
 *
 * <p>ASR 与 LLM 的切换规则**不一样**: LLM 可以"没吐字就换", ASR 不行 ——
 * 音频是连续喂给同一家识别的, 中途换厂商等于放弃已积累的上下文.
 * 所以这里的契约是: <b>在建连阶段</b>挑一家(按可用性 + open 成功与否), 之后不换.
 */
class FailoverAsrTest {

    /** 可编程的假 ASR: session 归属自己, close 别人家的 session 会抛(让组合类能挑出正确的那个). */
    private static final class StubAsr implements Asr {
        final String id;
        RuntimeException openError;
        final AudioFormat format;
        int opens;
        int closes;
        StubAsr own;

        StubAsr(String id) {
            this(id, null, null);
        }

        StubAsr(String id, RuntimeException openError) {
            this(id, openError, null);
        }

        StubAsr(String id, RuntimeException openError, AudioFormat format) {
            this.id = id;
            this.openError = openError;
            this.format = format;
        }

        @Override
        public Asr.AsrSession open(Consumer<Asr.AsrEvent> onEvent, Consumer<Throwable> onError) {
            opens++;
            if (openError != null) {
                throw openError;
            }
            final StubAsr me = this;
            Asr.AsrSession session = new Asr.AsrSession() {
                @Override
                public void sendAudio(byte[] pcm) {
                    onEvent.accept(new Asr.AsrEvent(Asr.AsrEvent.Type.PARTIAL, "partial"));
                }

                @Override
                public void endTurn() {
                    onEvent.accept(new Asr.AsrEvent(Asr.AsrEvent.Type.FINAL, "final"));
                }

                @Override
                public void close() {
                    me.closes++;
                }
            };
            own = this;
            return session;
        }

        @Override
        public void close(Asr.AsrSession session) {
            if (own == null || own != this) {
                throw new IllegalStateException("不是这一家的 session");
            }
            closes++;
        }

        @Override
        public AudioFormat inputFormat() {
            return format == null ? AudioFormat.pcm16(16000, 1) : format;
        }
    }

    private static Asr.AsrSession open(FailoverAsr asr, List<Asr.AsrEvent> sink) {
        return asr.open(e -> sink.add(e), t -> {
        });
    }

    @Test
    void 第一个可用的胜出() {
        StubAsr a = new StubAsr("a");
        StubAsr b = new StubAsr("b");
        FailoverAsr chain = new FailoverAsr(Arrays.asList(
                FailoverAsr.entry("a", a, null),
                FailoverAsr.entry("b", b, null)));

        List<Asr.AsrEvent> events = new ArrayList<Asr.AsrEvent>();
        Asr.AsrSession session = open(chain, events);

        assertNotNull(session);
        assertEquals(1, a.opens);
        assertEquals(0, b.opens);
        assertEquals("a", chain.activeName());
    }

    @Test
    void 不可用的实现被跳过() {
        StubAsr a = new StubAsr("a");
        StubAsr b = new StubAsr("b");
        FailoverAsr chain = new FailoverAsr(Arrays.asList(
                FailoverAsr.entry("a", a, () -> false),
                FailoverAsr.entry("b", b, () -> true)));

        List<Asr.AsrEvent> events = new ArrayList<Asr.AsrEvent>();
        open(chain, events);

        assertEquals(0, a.opens, "enabled=false 的不该被建连");
        assertEquals(1, b.opens);
        assertEquals("b", chain.activeName());
    }

    @Test
    void 建连失败顺延到下一家() {
        StubAsr a = new StubAsr("a", new IllegalStateException("401 unauthorized"));
        StubAsr b = new StubAsr("b");
        FailoverAsr chain = new FailoverAsr(Arrays.asList(
                FailoverAsr.entry("a", a, null),
                FailoverAsr.entry("b", b, null)));

        List<Asr.AsrEvent> events = new ArrayList<Asr.AsrEvent>();
        open(chain, events);

        assertEquals(1, a.opens);
        assertEquals(1, b.opens);
        assertEquals("b", chain.activeName());
    }

    @Test
    void 全都不可用时给出可读异常() {
        StubAsr a = new StubAsr("a");
        StubAsr b = new StubAsr("b");
        FailoverAsr chain = new FailoverAsr(Arrays.asList(
                FailoverAsr.entry("a", a, () -> false),
                FailoverAsr.entry("b", b, () -> false)));

        try {
            open(chain, new ArrayList<Asr.AsrEvent>());
            fail("应当抛异常");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("没有可用的 ASR"), "异常要说清：" + e.getMessage());
            assertNull(chain.activeName());
        }
    }

    @Test
    void 输入格式取第一个可用实现的() {
        StubAsr a = new StubAsr("a", null, AudioFormat.pcm16(8000, 1));
        StubAsr b = new StubAsr("b", null, AudioFormat.pcm16(24000, 2));
        FailoverAsr chain = new FailoverAsr(Arrays.asList(
                FailoverAsr.entry("a", a, () -> false),
                FailoverAsr.entry("b", b, null)));

        assertEquals(AudioFormat.pcm16(24000, 2), chain.inputFormat());
    }

    @Test
    void 关闭会话要找到真正持有它的那一家() {
        StubAsr a = new StubAsr("a");
        StubAsr b = new StubAsr("b");
        FailoverAsr chain = new FailoverAsr(Arrays.asList(
                FailoverAsr.entry("a", a, null),
                FailoverAsr.entry("b", b, null)));

        List<Asr.AsrEvent> events = new ArrayList<Asr.AsrEvent>();
        Asr.AsrSession session = open(chain, events);
        chain.close(session);

        assertEquals(1, a.closes, "会话是 a 开的, 关闭也要落到 a");
        assertEquals(0, b.closes);
    }

    @Test
    void 关闭会话时优先关持有会话的那一家而不是第一家() {
        // 第一家的 close 不抛异常(它不认识这个 session)⇒ 不能靠"异常"来定位主人
        StubAsr a = new StubAsr("a");
        StubAsr b = new StubAsr("b");
        FailoverAsr chain = new FailoverAsr(Arrays.asList(
                FailoverAsr.entry("a", a, null),
                FailoverAsr.entry("b", b, null)));

        // 让 a 的建连失败, 会话落到 b
        a.openError = new IllegalStateException("connect refused");
        List<Asr.AsrEvent> events = new ArrayList<Asr.AsrEvent>();
        Asr.AsrSession session = open(chain, events);
        assertEquals("b", chain.activeName());

        chain.close(session);
        assertEquals(1, b.closes, "会话是 b 开的, 关闭必须落到 b");
        assertEquals(0, a.closes, "a 没持有这个会话, 不该由它来关");
    }

    @Test
    void 事件原样透传给上层() {
        StubAsr a = new StubAsr("a");
        FailoverAsr chain = new FailoverAsr(Arrays.asList(FailoverAsr.entry("a", a, null)));
        List<Asr.AsrEvent> events = new ArrayList<Asr.AsrEvent>();
        Asr.AsrSession session = open(chain, events);

        session.sendAudio(new byte[]{1, 2, 3});
        session.endTurn();

        assertEquals(2, events.size());
        assertEquals(Asr.AsrEvent.Type.PARTIAL, events.get(0).type);
        assertEquals(Asr.AsrEvent.Type.FINAL, events.get(1).type);
        assertEquals(Arrays.asList("a"), chain.names());
        assertFalse(chain.names().isEmpty());
    }
}
