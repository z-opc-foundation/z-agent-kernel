package com.zifang.z.agent.kernel.memory;

/**
 * 记忆编排 SPI — agent 主循环与跨会话记忆之间的桥 (对齐 hermes memory_manager.py).
 *
 * <p>与 {@link MemoryStore}(纯存储) 的分工: Provider 负责编排时机 —
 * turn 前 prefetch(把相关记忆注入系统提示), turn 后 sync(把该记的内容写回),
 * 以及 agent 主动 save. 实现方可对接外部服务 (mem0/honcho 等), 也可以只是本地 markdown.
 */
public interface MemoryProvider {

    /** @return provider 标识 (如 "local-md" / "mem0"). */
    String name();

    /**
     * turn 前 prefetch.
     *
     * @param userMessage 本轮用户输入
     * @return 应注入系统提示的记忆文本 (无则 null/空串)
     */
    String prefetch(String userMessage);

    /** turn 后同步 — 实现方自行判断本轮是否产生了值得长期记住的内容. */
    void sync(String userMessage, String assistantReply);

    /** agent (memory 工具) 或用户主动写入一条记忆. */
    void save(String content);
}
