package com.zifang.z.agent.kernel.realtime;

import org.junit.jupiter.api.Test;

import com.zifang.z.agent.kernel.realtime.Realtime;
import com.zifang.z.agent.kernel.realtime.Realtime.RealtimeEvent;
import com.zifang.z.agent.kernel.realtime.Realtime.RealtimeSession;
import com.zifang.z.agent.kernel.types.AudioFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Realtime} 契约测试.
 *
 * <p>这个模块以前是"有名字没东西"的一块: 零测试、零消费者, 契约里也没有"用户说完了"这个信号.
 * 这里把改完后的三条语义钉住 —— 中间结果标志、音频格式随字节走、轮次可结束.
 */
class RealtimeContractTest {

    @Test
    void 事件带中间结果标志与音频格式() {
        AudioFormat fmt = AudioFormat.pcm16(24000, 1);
        RealtimeEvent audio = new RealtimeEvent(RealtimeEvent.Type.ASSISTANT_AUDIO, null,
                new byte[]{1, 2}, null, null, false, fmt);
        assertEquals(RealtimeEvent.Type.ASSISTANT_AUDIO, audio.type);
        assertNotNull(audio.audioFormat, "音频事件必须带格式, 否则消费方没法播");
        assertEquals(24000, audio.audioFormat.getSampleRate());
        assertFalse(audio.partial);

        RealtimeEvent partial = new RealtimeEvent(RealtimeEvent.Type.USER_TRANSCRIPT, "你",
                null, null, null, true, null);
        assertTrue(partial.partial, "中间结果必须能标出来");
        assertEquals("你", partial.text);
    }

    @Test
    void 旧构造器保持兼容() {
        RealtimeEvent e = new RealtimeEvent(RealtimeEvent.Type.USER_TRANSCRIPT, "你好", null, null, null);
        assertFalse(e.partial, "旧构造器默认不是中间结果");
        assertNull(e.audioFormat, "旧构造器没有格式");
    }

    @Test
    void 会话有结束一轮的信号() {
        final List<String> calls = new ArrayList<String>();
        Realtime realtime = new Realtime() {
            @Override
            public RealtimeSession open(Consumer<RealtimeEvent> onEvent, Consumer<Throwable> onError) {
                return new RealtimeSession() {
                    @Override
                    public void sendAudio(byte[] pcm) {
                        calls.add("sendAudio");
                    }

                    @Override
                    public void sendText(String text) {
                        calls.add("sendText");
                    }

                    @Override
                    public void endTurn() {
                        calls.add("endTurn");
                    }

                    @Override
                    public void interrupt() {
                        calls.add("interrupt");
                    }

                    @Override
                    public void close() {
                        calls.add("close");
                    }
                };
            }

            @Override
            public void close(RealtimeSession session) {
                session.close();
            }
        };

        RealtimeSession session = realtime.open(new Consumer<RealtimeEvent>() {
            @Override
            public void accept(RealtimeEvent e) {
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable t) {
            }
        });
        session.sendAudio(new byte[]{0});
        session.sendText("hi");
        session.endTurn();
        session.interrupt();
        session.close();

        assertEquals(java.util.Arrays.asList("sendAudio", "sendText", "endTurn", "interrupt", "close"), calls);
    }

    @Test
    void 默认音频格式是16k单声道PCM() {
        Realtime realtime = new Realtime() {
            @Override
            public RealtimeSession open(Consumer<RealtimeEvent> onEvent, Consumer<Throwable> onError) {
                return null;
            }

            @Override
            public void close(RealtimeSession session) {
            }
        };
        assertEquals("pcm", realtime.audioFormat().getEncoding());
        assertEquals(16000, realtime.audioFormat().getSampleRate());
        assertEquals(1, realtime.audioFormat().getChannels());
    }
}
