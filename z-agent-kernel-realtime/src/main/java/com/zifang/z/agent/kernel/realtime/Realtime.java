package com.zifang.z.agent.kernel.realtime;

import java.util.function.Consumer;

import com.zifang.z.agent.kernel.types.AudioFormat;

/**
 * Realtime SPI — 实时多模态对话(语音进 + 语音出 + 工具调用).
 *
 * <p>跟 kernel.llm 的差异: 普通 chat 是"文本往返", realtime 是"持续会话 + 低延迟语音".
 *
 * <p><b>实现方</b>: 语音服务侧. ⚠️ 历史上这里写的是"z-msg", 但 z-msg 的 {@code Realtime} 是
 * **消息推送**(RealtimePublisher / RealtimeTransport), 与语音无关 —— 那条注释是错的, 已纠正.
 *
 * <p><b>识别侧</b>: {@code Realtime} 只暴露"推一帧语音", 不管这帧怎么被识别;
 * 要单独接流式识别(SSR)时用 {@code kernel.asr} 的 {@code Asr}, 两者可组合使用.
 *
 * @author yuku123
 */
public interface Realtime {

    /**
     * 打开一个实时会话.
     *
     * @param onEvent  事件回调(用户语音识别结果 / 模型回复文本 / 模型回复音频 / 工具调用)
     * @param onError  错误回调
     * @return 会话句柄, 用于发语音帧 / 发文本 / 结束一轮 / 打断 / 关闭
     */
    RealtimeSession open(Consumer<RealtimeEvent> onEvent, Consumer<Throwable> onError);

    /**
     * 关闭会话, 释放底层连接.
     */
    void close(RealtimeSession session);

    /**
     * 会话要吃什么样的音频.
     *
     * <p>默认 16kHz / PCM16 / 单声道 —— 与 {@code kernel.asr} 的 {@code Asr.inputFormat()} 同档.
     */
    default AudioFormat audioFormat() {
        return AudioFormat.pcm16(16000, 1);
    }

    interface RealtimeSession {
        /** 推一帧用户语音(16kHz PCM). */
        void sendAudio(byte[] pcm);

        /** 推一段用户文本. */
        void sendText(String text);

        /**
         * 结束当前这一轮 —— "用户说完了".
         *
         * <p>没有这个信号时, 实现方只能靠自己的 VAD 断句来决定何时应答;
         * 有它之后, 轮次边界可以由上层(结合静音时长)决定, 打断({@link #interrupt()})也才有"轮"可打断.
         */
        void endTurn();

        /** 中断模型当前回复. */
        void interrupt();

        /** 关闭. */
        void close();
    }

    /**
     * 实时会话事件.
     */
    final class RealtimeEvent {
        public final Type type;
        public final String text;
        /** 音频字节 —— 读它之前先看 {@link #audioFormat}, 裸字节不带格式没法播. */
        public final byte[] audio;
        public final String toolName;
        public final String toolCallId;
        /** true = 中间结果(还会被后续结果修正), false = 最终结果. */
        public final boolean partial;
        /** {@link #audio} 的格式; 非音频事件为 null. */
        public final AudioFormat audioFormat;

        public RealtimeEvent(Type type, String text, byte[] audio, String toolName, String toolCallId) {
            this(type, text, audio, toolName, toolCallId, false, null);
        }

        public RealtimeEvent(Type type, String text, byte[] audio, String toolName, String toolCallId,
                             boolean partial, AudioFormat audioFormat) {
            this.type = type;
            this.text = text;
            this.audio = audio;
            this.toolName = toolName;
            this.toolCallId = toolCallId;
            this.partial = partial;
            this.audioFormat = audioFormat;
        }

        public enum Type {
            USER_TRANSCRIPT,    // 用户语音识别结果(partial 标志区分中间/最终)
            ASSISTANT_TEXT,     // 模型文本增量
            ASSISTANT_AUDIO,    // 模型音频增量(看 audioFormat)
            TOOL_CALL,          // 模型发起工具调用
            TOOL_RESULT,        // 工具调用结果
            SESSION_DONE        // 会话结束
        }
    }
}
