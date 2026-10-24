package com.zifang.z.agent.kernel.asr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Asr} 契约测试.
 *
 * <p>这个模块以前不存在, 也就没有测试能守住"识别侧的语义".
 * 这里把三条最容易实现歪的语义钉死: 中间/最终结果的区分、轮次边界、输入音频格式.
 */
class AsrContractTest {

    /** 假实现: 收到音频就吐一条中间结果, endTurn 时吐一条最终结果. */
    private static final class FakeAsr implements Asr {
        final List<byte[]> frames = new ArrayList<byte[]>();
        final List<Asr.AsrEvent> events = new ArrayList<Asr.AsrEvent>();
        boolean closed;

        @Override
        public Asr.AsrSession open(Consumer<Asr.AsrEvent> onEvent, Consumer<Throwable> onError) {
            return new Asr.AsrSession() {
                @Override
                public void sendAudio(byte[] pcm) {
                    frames.add(pcm);
                    onEvent.accept(new Asr.AsrEvent(Asr.AsrEvent.Type.PARTIAL, "你"));
                }

                @Override
                public void endTurn() {
                    onEvent.accept(new Asr.AsrEvent(Asr.AsrEvent.Type.FINAL, "你好"));
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }

        @Override
        public void close(Asr.AsrSession session) {
            session.close();
        }
    }

    private static Asr.AsrSession open(FakeAsr asr) {
        return asr.open(new Consumer<Asr.AsrEvent>() {
            @Override
            public void accept(Asr.AsrEvent e) {
                asr.events.add(e);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable t) {
            }
        });
    }

    @Test
    void 事件区分中间与最终结果() {
        FakeAsr asr = new FakeAsr();
        Asr.AsrSession session = open(asr);
        assertTrue(asr.events.isEmpty(), "刚建连不该有事件");

        session.sendAudio(new byte[]{1, 2, 3});
        assertEquals(1, asr.frames.size(), "音频帧要原样递给实现方");
        assertEquals(1, asr.events.size());
        assertEquals(Asr.AsrEvent.Type.PARTIAL, asr.events.get(0).type);
        assertEquals("你", asr.events.get(0).text);

        session.endTurn();
        assertEquals(2, asr.events.size());
        assertEquals(Asr.AsrEvent.Type.FINAL, asr.events.get(1).type);
        assertEquals("你好", asr.events.get(1).text);
    }

    @Test
    void 轮次结束是显式信号() {
        FakeAsr asr = new FakeAsr();
        Asr.AsrSession session = open(asr);
        session.sendAudio(new byte[]{0});
        assertEquals(1, asr.events.size(), "没结束轮次前只有中间结果");
        session.endTurn();
        assertEquals(Asr.AsrEvent.Type.FINAL, asr.events.get(asr.events.size() - 1).type);
    }

    @Test
    void 默认输入格式是16k单声道PCM() {
        Asr asr = new FakeAsr();
        assertNotNull(asr.inputFormat());
        assertEquals("pcm", asr.inputFormat().getEncoding());
        assertEquals(16000, asr.inputFormat().getSampleRate());
        assertEquals(1, asr.inputFormat().getChannels());
        assertEquals(16, asr.inputFormat().getBitsPerSample());
    }

    @Test
    void 关闭会话会释放资源() {
        FakeAsr asr = new FakeAsr();
        Asr.AsrSession session = open(asr);
        asr.close(session);
        assertTrue(asr.closed, "close(session) 必须把会话关掉");
    }
}
