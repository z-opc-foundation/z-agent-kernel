package com.zifang.z.agent.kernel.formatter;

/**
 * 格式化器 SPI. 把 kernel 内部 Msg 列表转成目标协议 (OpenAI / Anthropic / 自定义) JSON,
 * 或反向把外部 JSON 解析回 Msg 列表.
 *
 * <p>protocol: 协议名 ("openai-chat" / "anthropic-messages" / ...).
 * <p>format: 序列化方向 (request/response).
 */
public interface Formatter<T> {

    String getProtocol();

    Direction getDirection();

    String format(T payload);

    T parse(String raw);

    enum Direction {
        REQUEST,
        RESPONSE,
        BIDIRECTIONAL
    }
}