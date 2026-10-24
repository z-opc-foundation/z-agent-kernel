package com.zifang.z.agent.kernel.asr;

import java.util.function.Consumer;

import com.zifang.z.agent.kernel.types.AudioFormat;

/**
 * Asr — 流式语音识别(SSR) SPI.
 *
 * <p><b>为什么补这个面</b>: kernel 原来只有 {@code Tts} 与 {@code Realtime} 两个语音契约,
 * "语音进"这一半(识别)无处安放 ⇒ 每个想接流式识别的项目都得自己写一遍协议适配.
 * 这里把识别侧补成与 {@code Realtime} 同形的契约.
 *
 * <p><b>与 Realtime 的分工</b>:
 * <ul>
 *   <li>{@code Asr} 只管"语音 → 文本", 不管模型, 不管工具调用;</li>
 *   <li>{@code Realtime} 管"一整轮"的状态机(识别 → 生成 → 播报 → 打断),
 *       其内部要用识别时, 走的就是本接口.</li>
 * </ul>
 *
 * <p><b>轮次边界</b>: {@link AsrSession#endTurn()} 是"用户说完了"的信号.
 * 服务端若自带 VAD 断句(百炼 paraformer 之类), 可以在断句后自行 {@code endTurn};
 * 服务端不带 VAD 时, 由上层根据静音时长调 {@code endTurn}.
 *
 * <p>实现方: 语音服务侧(百炼 paraformer-realtime / MiniMax asr-1.0 / 自建 Whisper 等).
 *
 * @author yuku123
 */
public interface Asr {

    /**
     * 打开一个识别会话.
     *
     * @param onEvent 识别结果回调(中间结果 / 最终结果)
     * @param onError 错误回调
     * @return 会话句柄, 用于推音频帧 / 结束一轮 / 关闭
     */
    AsrSession open(Consumer<AsrEvent> onEvent, Consumer<Throwable> onError);

    /**
     * 关闭会话, 释放底层连接.
     */
    void close(AsrSession session);

    /**
     * 会话要吃什么样的音频.
     *
     * <p>默认 16kHz / PCM16 / 单声道 —— 电话与语音识别的通用档.
     */
    default AudioFormat inputFormat() {
        return AudioFormat.pcm16(16000, 1);
    }

    interface AsrSession {

        /**
         * 推一帧用户语音.
         *
         * @param pcm 按 {@link Asr#inputFormat()} 描述的音频字节
         */
        void sendAudio(byte[] pcm);

        /**
         * 结束当前这一轮(用户说完了).
         *
         * <p>实现方收到后应当把已收到的音频收尾成一条最终结果.
         */
        void endTurn();

        /** 关闭. */
        void close();
    }

    /**
     * 识别事件.
     */
    final class AsrEvent {

        public final Type type;
        public final String text;

        public AsrEvent(Type type, String text) {
            this.type = type;
            this.text = text;
        }

        public enum Type {
            /** 中间结果(用户还没说完, 文本会继续被修正). */
            PARTIAL,
            /** 一句说完的最终结果. */
            FINAL
        }
    }
}
