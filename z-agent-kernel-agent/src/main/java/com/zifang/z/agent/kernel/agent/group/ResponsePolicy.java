package com.zifang.z.agent.kernel.agent.group;

import java.util.List;

/**
 * 「这条发言该谁接」策略 SPI —— 群聊能不能跑下去全看它.
 *
 * <p>实现者只需回答"哪些 agent 本轮要开口", 组内其余机制（每轮至多一次、超轮次收敛、
 * 上下文截断、agent 调用）由 {@link GroupChatRunner} 兜住。想接上自己的派单/仲裁逻辑
 * （例如 z-team 的 observer 门控 + 认领表决）就实现这个接口, 不要去改 runner.
 *
 * <p>约定: 返回空列表 = 没人接, 群就此收敛.
 */
public interface ResponsePolicy {

    List<String> selectResponders(GroupChatContext context);
}
