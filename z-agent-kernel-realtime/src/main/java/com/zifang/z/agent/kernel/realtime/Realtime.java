package com.zifang.z.agent.kernel.realtime;

import java.util.function.Consumer;

/**
 * Realtime SPI — 实时多模态对话(语音进 + 语音出 + 工具调用).
 *
 * <p>实现方: z-msg.
 *
 * <p>跟 kernel.llm 的差异: 普通 chat 是"文本往返", realtime 是"持续会话 + 低延迟语音".
 */
public interface Realtime {

    /**
     * 打开一个实时会话.
     *
     * @param onEvent  事件回调(用户语音识别结果 / 模型回复文本 / 模型回复音频 / 工具调用)
     * @param onError  错误回调
     * @return 会话句柄, 用于发语音帧 / 发文本 / 关闭
     */
    RealtimeSession open(Consumer<RealtimeEvent> onEvent, Consumer<Throwable> onError);

    /**
     * 关闭会话, 释放底层连接.
     */
    void close(RealtimeSession session);

    interface RealtimeSession {
        /** 推一帧用户语音(16kHz PCM). */
        void sendAudio(byte[] pcm);

        /** 推一段用户文本. */
        void sendText(String text);

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
        public final byte[] audio;
        public final String toolName;
        public final String toolCallId;

        public RealtimeEvent(Type type, String text, byte[] audio, String toolName, String toolCallId) {
            this.type = type;
            this.text = text;
            this.audio = audio;
            this.toolName = toolName;
            this.toolCallId = toolCallId;
        }

        public enum Type {
            USER_TRANSCRIPT,    // 用户语音识别结果
            ASSISTANT_TEXT,     // 模型文本增量
            ASSISTANT_AUDIO,    // 模型音频增量
            TOOL_CALL,          // 模型发起工具调用
            TOOL_RESULT,        // 工具调用结果
            SESSION_DONE        // 会话结束
        }
    }
}