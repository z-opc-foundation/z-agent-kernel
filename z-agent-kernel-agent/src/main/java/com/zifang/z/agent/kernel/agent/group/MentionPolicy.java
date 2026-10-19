package com.zifang.z.agent.kernel.agent.group;

import java.util.ArrayList;
import java.util.List;

/**
 * 只让被 @ 到的 agent 开口, 且第一轮之后不再追加发言者 —— 群聊在"人点谁谁答"模式下天然一轮收敛.
 */
public final class MentionPolicy implements ResponsePolicy {

    /** @ 前缀; 真人可以写 "@张三" 或直接给 mentions 列表, 两种都认. */
    public static final String MENTION_PREFIX = "@";

    @Override
    public List<String> selectResponders(GroupChatContext context) {
        if (context.getRound() > 0) {
            return java.util.Collections.emptyList();
        }
        // 不在这里过滤"名册里没有 / 不是 agent": 那是 runner 的校验, 过滤掉就等于把
        // 名册与 agent 表对不上的配置错误静默吞了。原始点名名单原样交上去。
        List<String> out = new ArrayList<String>();
        for (String id : mentioned(context.getLatest())) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 显式 mentions 优先; 没有就从 content 里扫 @前缀的 id. */
    public static List<String> mentioned(GroupMessage message) {
        if (!message.getMentions().isEmpty()) {
            return message.getMentions();
        }
        List<String> out = new ArrayList<String>();
        for (String token : message.getContent().split("[\\s,，、]+")) {
            if (token.startsWith(MENTION_PREFIX) && token.length() > MENTION_PREFIX.length()) {
                out.add(token.substring(MENTION_PREFIX.length()));
            }
        }
        return out;
    }
}
