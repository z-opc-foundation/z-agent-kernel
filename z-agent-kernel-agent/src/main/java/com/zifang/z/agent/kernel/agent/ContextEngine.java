package com.zifang.z.agent.kernel.agent;

import com.zifang.z.agent.kernel.message.Msg;

import java.util.List;

/**
 * 上下文引擎 SPI — 决定会话历史何时压缩、怎么压缩.
 *
 * <p>语义对齐 hermes agent/context_engine.py: 可插拔, 默认实现为"中段摘要"压缩器
 * (保留 system + 头部 + 最近 N 轮原文, 中间用廉价辅助模型总结, 摘要强制保留事实承诺).
 * 上层可经注册表整体替换 (如第三方长上下文引擎).
 */
public interface ContextEngine {

    /** 会话开始时调用, 引擎可重置内部记账. */
    void onSessionStart();

    /** 每次响应后回填 token 用量. */
    void update(int inputTokens, int outputTokens);

    /** @return 主循环在发送下一次请求前是否应先执行 {@link #compress}. */
    boolean shouldCompress();

    /**
     * 压缩历史.
     *
     * @param history    完整对话历史 (不含 system)
     * @param summarizer 摘要函数 — 引擎决定中段是哪些消息, 交给辅助模型总结
     * @return 压缩后的新历史 (原列表不动)
     */
    List<Msg> compress(List<Msg> history, Summarizer summarizer);

    /** 摘要函数 — 由上层绑定到具体的辅助 LLM. */
    interface Summarizer {
        String summarize(List<Msg> middle);
    }
}
