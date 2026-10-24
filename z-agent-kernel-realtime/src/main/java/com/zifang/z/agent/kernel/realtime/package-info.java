/**
 * realtime — Realtime 接口 (SPI only).
 *
 * <p>实现方: 语音服务侧. ⚠️ 历史上这里写的"z-msg"是错的 —— z-msg 的 {@code Realtime}
 * 是**消息推送**语义(RealtimePublisher / RealtimeTransport), 与语音无关.
 *
 * <p>识别侧另见 {@code kernel.asr} 的 {@code Asr}.
 *
 * @author yuku123
 */
package com.zifang.z.agent.kernel.realtime;
