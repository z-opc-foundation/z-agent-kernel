package com.zifang.z.agent.kernel.tts;

import java.io.InputStream;
import java.util.function.Consumer;

import com.zifang.z.agent.kernel.types.AudioFormat;

/**
 * Tts (Text-to-Speech) SPI — 文本转语音.
 *
 * <p>输出 PCM/MP3/WAV 流, 由调用方决定播放/落盘.
 *
 * <p><b>两条出口</b>:
 * <ul>
 *   <li>{@link #synthesize}: 一次性拿到完整音频 —— 适合"生成音频文件", 不适合实时对话;</li>
 *   <li>{@link #synthesizeStream}: 边合成边给 —— 语音对话要的就是这个, 否则首字延迟被整段合成时间吃掉.
 *       默认实现退化成"一次性合成后整块吐出来", 所以已有实现方零改动即可继续工作.</li>
 * </ul>
 *
 * <p>实现方: 语音服务侧(MiniMax t2a_v2 / 百炼 CosyVoice / 自建 TTS 等).
 *
 * @author yuku123
 */
public interface Tts {

    /**
     * @param text    待合成文本
     * @param voice   音色 ID(如 "alloy" / "zh_female_1")
     * @param options 语速/音调/输出格式等(可选)
     * @return 音频字节流(由调用方 close)
     */
    InputStream synthesize(String text, String voice, TtsOptions options);

    /**
     * @return 默认音色 ID
     */
    String defaultVoice();

    /**
     * 流式合成 —— 一边合成一边把音频块交出去.
     *
     * <p>默认实现: 调 {@link #synthesize} 拿到完整字节后整块吐一次, 再回调 {@code onComplete}.
     * 支持真流式的实现方覆写它即可.
     *
     * @param onChunk   音频块回调(顺序即播放顺序)
     * @param onError   错误回调
     * @param onComplete 本次合成结束回调
     */
    default void synthesizeStream(String text, String voice, TtsOptions options,
                                  Consumer<byte[]> onChunk,
                                  Consumer<Throwable> onError,
                                  Runnable onComplete) {
        try {
            InputStream in = synthesize(text, voice, options);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    onChunk.accept(chunk);
                }
            } finally {
                in.close();
            }
            onComplete.run();
        } catch (Throwable t) {
            onError.accept(t);
        }
    }

    /**
     * 打开一个增量合成会话 —— 边喂文本边出音频.
     *
     * <p><b>为什么需要它</b>: {@link #synthesizeStream} 只接受"一整段文本",
     * 而语音对话里 LLM 的输出是<em>逐字</em>到的. 要在第一句就出声,
     * 就得能"喂一句、听一句" ⇒ 需要一个能追加文本的会话.
     *
     * <p>默认实现: 缓冲所有 {@code speak} 的文本, {@code finish()} 时一次性走
     * {@link #synthesizeStream} —— 语义正确但首字延迟高; 支持增量的实现方覆写它.
     *
     * @param voice   音色 ID
     * @param options 语速/音调/输出格式
     * @param onChunk 音频块回调
     * @param onError 错误回调
     * @param onComplete 本次会话音频吐完回调
     */
    default TtsStream openStream(String voice, TtsOptions options,
                                  Consumer<byte[]> onChunk,
                                  Consumer<Throwable> onError,
                                  Runnable onComplete) {
        final StringBuilder buffer = new StringBuilder();
        final boolean[] stopped = {false};
        return new TtsStream() {
            @Override
            public void speak(String text) {
                if (!stopped[0] && text != null) {
                    buffer.append(text);
                }
            }

            @Override
            public void finish() {
                if (stopped[0] || buffer.length() == 0) {
                    // 被掐断或压根没有文本 ⇒ 没什么可合成的, 直接收尾
                    onComplete.run();
                    return;
                }
                synthesizeStream(buffer.toString(), voice, options, onChunk, onError, onComplete);
            }

            @Override
            public void stop() {
                stopped[0] = true;
                buffer.setLength(0);
            }

            @Override
            public void close() {
                stopped[0] = true;
                buffer.setLength(0);
            }
        };
    }

    /**
     * 增量合成会话.
     */
    interface TtsStream {
        /** 追加一段文本(可以多次调用). */
        void speak(String text);

        /** 文本送完 —— 之后应当把剩余音频吐完并回调完成. */
        void finish();

        /** 立刻掐断(用户打断). */
        void stop();

        /** 关闭. */
        void close();
    }

    final class TtsOptions {
        /** 语速, 0.5 ~ 2.0(浮点倍率; null = 用实现方自己的默认). */
        public final Float speed;
        /**
         * 音高偏移(半音), 一般 -12 ~ 12.
         *
         * <p>⚠️ <b>必须是整数</b> —— MiniMax {@code t2a_v2} 的 {@code voice_setting.pitch}
         * 只接受整型, 传 {@code 0.0} / {@code 1.0} 这类浮点会被服务端拒成
         * {@code 2013 invalid params, invalid message format}(实测 2026-10-23).
         * 音高是离散半音语义, 浮点没有意义, 故此处用 {@code int} 而不是 {@code Float}
         * —— 从类型上就杜绝实现方把它序列化成浮点.
         */
        public final int pitch;
        /** 输出音频格式(编码 / 采样率 / 声道 / 位深). */
        public final AudioFormat audioFormat;

        public TtsOptions(Float speed, int pitch, AudioFormat audioFormat) {
            this.speed = speed;
            this.pitch = pitch;
            this.audioFormat = audioFormat;
        }

        /** 默认: 1.0 语速 / 0 音高偏移 / PCM16 24kHz 单声道. */
        public static TtsOptions defaults() {
            return new TtsOptions(1.0f, 0, AudioFormat.pcm16(24000, 1));
        }
    }
}
