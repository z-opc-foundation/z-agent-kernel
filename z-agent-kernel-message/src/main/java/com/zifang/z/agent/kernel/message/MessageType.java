package com.zifang.z.agent.kernel.message;

/**
 * 消息内容类型: text / image / audio / video / multimodal / tool_call / tool_result.
 */
public enum MessageType {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    MULTIMODAL,
    TOOL_CALL,
    TOOL_RESULT
}