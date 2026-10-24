/**
 * asr — 流式语音识别(SSR)接口 (SPI only).
 *
 * <p>补上 kernel 语音链路缺失的"语音进"这一半: 识别侧只出文本,
 * 轮次状态机与打断在 {@code realtime} 模块.
 *
 * @author yuku123
 */
package com.zifang.z.agent.kernel.asr;
