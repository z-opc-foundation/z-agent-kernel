package com.zifang.z.agent.kernel.types;

/**
 * 消息角色, kernel 内部共享 enum.
 *
 * <p>基础四元: USER (用户输入) / ASSISTANT (LLM 输出) / SYSTEM (系统提示) / TOOL (工具回执).
 * <p>扩展: FUNCTION (兼容 OpenAI function calling 历史命名).
 */
public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
    FUNCTION
}