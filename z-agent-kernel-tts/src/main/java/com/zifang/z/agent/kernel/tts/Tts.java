package com.zifang.z.agent.kernel.tts;

import java.io.InputStream;

/**
 * TTS (Text-to-Speech) SPI — 文本转语音.
 *
 * <p>实现方: z-msg.
 *
 * <p>输出 PCM/MP3/WAV 流, 由调用方决定播放/落盘.
 */
public interface Tts {

    /**
     * @param text    待合成文本
     * @param voice   音色 ID(如 "alloy" / "zh_female_1")
     * @param options 语速/音调等(可选)
     * @return 音频字节流(由调用方 close)
     */
    InputStream synthesize(String text, String voice, TtsOptions options);

    /**
     * @return 默认音色 ID
     */
    String defaultVoice();

    final class TtsOptions {
        public final Float speed;       // 0.5 ~ 2.0
        public final Float pitch;       // 0.5 ~ 2.0
        public final String format;     // "mp3" / "wav" / "pcm"

        public TtsOptions(Float speed, Float pitch, String format) {
            this.speed = speed;
            this.pitch = pitch;
            this.format = format == null ? "mp3" : format;
        }

        public static TtsOptions defaults() {
            return new TtsOptions(1.0f, 1.0f, "mp3");
        }
    }
}