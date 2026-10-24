package com.zifang.z.agent.kernel.tts;

import org.junit.jupiter.api.Test;

import com.zifang.z.agent.kernel.tts.Tts;
import com.zifang.z.agent.kernel.tts.Tts.TtsOptions;
import com.zifang.z.agent.kernel.types.AudioFormat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Tts} 契约测试.
 *
 * <p>重点是 {@code synthesizeStream} 的 default 实现 —— 它决定了"已有实现方零改动"这条承诺成不成立.
 */
class TtsContractTest {

    /** 假实现: synthesize 返回固定字节, 不实现流式 ⇒ 走 default 路径. */
    private static class FakeTts implements Tts {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        String lastVoice;

        @Override
        public InputStream synthesize(String text, String voice, TtsOptions options) {
            lastVoice = voice;
            return new ByteArrayInputStream(payload);
        }

        @Override
        public String defaultVoice() {
            return "fake-voice";
        }
    }

    /** 会抛的实现: 用来验证错误走 onError 而不是吞掉. */
    private static final class BoomTts extends FakeTts {
        @Override
        public InputStream synthesize(String text, String voice, TtsOptions options) {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void 默认流式实现整块吐出并回调完成() {
        FakeTts tts = new FakeTts();
        final List<byte[]> chunks = new ArrayList<byte[]>();
        final boolean[] done = {false};
        final Throwable[] err = {null};

        tts.synthesizeStream("你好", "v1", TtsOptions.defaults(),
                chunk -> chunks.add(chunk),
                t -> err[0] = t,
                () -> done[0] = true);

        assertEquals(1, chunks.size(), "default 实现应把整段音频作为一块吐出来");
        assertEquals(5, chunks.get(0).length);
        assertTrue(done[0], "必须回调完成");
        assertEquals(null, err[0], "不该有错误");
        assertEquals("v1", tts.lastVoice, "voice 要透传给 synthesize");
    }

    @Test
    void 流式出错走onError() {
        BoomTts tts = new BoomTts();
        final boolean[] done = {false};
        final Throwable[] err = {null};

        tts.synthesizeStream("你好", null, null,
                chunk -> {
                },
                t -> err[0] = t,
                () -> done[0] = true);

        assertFalse(done[0], "出错时不该回调完成");
        assertTrue(err[0] instanceof IllegalStateException, "错误要原样传给 onError");
    }

    @Test
    void 默认参数是PCM16_24k单声道() {
        TtsOptions o = TtsOptions.defaults();
        assertEquals(1.0f, o.speed, 0.0001f);
        assertEquals(0, o.pitch);
        assertEquals("pcm", o.audioFormat.getEncoding());
        assertEquals(24000, o.audioFormat.getSampleRate());
        assertEquals(1, o.audioFormat.getChannels());
        assertEquals(16, o.audioFormat.getBitsPerSample());
    }

    @Test
    void 音高偏移是整型半音_避免厂商拒浮点() {
        // MiniMax t2a_v2 对浮点 pitch 返 2013 invalid message format(实测 2026-10-23)
        // ⇒ 契约层用 int, 从类型上杜绝实现方把它序列化成 0.0 / 1.0
        TtsOptions o = TtsOptions.defaults();
        assertEquals(0, o.pitch, "默认音高偏移必须是 0(不是 1.0)");
        TtsOptions custom = new TtsOptions(1.0f, -2, AudioFormat.pcm16(16000, 1));
        assertEquals(-2, custom.pitch);
    }

    @Test
    void 音频格式可以自定义() {
        AudioFormat fmt = new AudioFormat(AudioFormat.MP3, 32000, 2, 16);
        TtsOptions o = new TtsOptions(1.5f, 0, fmt);
        assertEquals("mp3", o.audioFormat.getEncoding());
        assertEquals(32000, o.audioFormat.getSampleRate());
        assertEquals(2, o.audioFormat.getChannels());
        assertEquals(1.5f, o.speed, 0.0001f);
    }

    /** 记下每次 synthesize 被喂了什么, 用来验证增量会话的缓冲语义. */
    private static final class RecordingTts implements Tts {
        final List<String> texts = new ArrayList<String>();

        @Override
        public InputStream synthesize(String text, String voice, TtsOptions options) {
            texts.add(text);
            return new ByteArrayInputStream(new byte[]{7});
        }

        @Override
        public String defaultVoice() {
            return "rec";
        }
    }

    @Test
    void 增量会话默认缓冲到finish再合成() {
        RecordingTts tts = new RecordingTts();
        final List<byte[]> chunks = new ArrayList<byte[]>();
        final boolean[] done = {false};

        Tts.TtsStream stream = tts.openStream("v", TtsOptions.defaults(),
                chunk -> chunks.add(chunk),
                t -> {
                },
                () -> done[0] = true);

        stream.speak("你好");
        stream.speak("，世界");
        assertTrue(tts.texts.isEmpty(), "还没 finish 就不该开始合成");
        assertFalse(done[0]);

        stream.finish();
        assertEquals(1, tts.texts.size());
        assertEquals("你好，世界", tts.texts.get(0), "多段文本要按顺序拼起来");
        assertEquals(1, chunks.size(), "合成结果要交给 onChunk");
        assertTrue(done[0]);
    }

    @Test
    void 增量会话stop后不再合成() {
        RecordingTts tts = new RecordingTts();
        Tts.TtsStream stream = tts.openStream("v", TtsOptions.defaults(),
                chunk -> {
                }, t -> {
                }, () -> {
                });
        stream.speak("半句话");
        stream.stop();
        stream.finish();
        assertTrue(tts.texts.isEmpty(), "stop 之后不该再合成");
    }
}
