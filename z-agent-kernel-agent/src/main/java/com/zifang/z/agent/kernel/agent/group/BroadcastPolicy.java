package com.zifang.z.agent.kernel.agent.group;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 广播模式: 每次发言都把话递给全体 agent, 但**每个人在一次 run 里至多开口一次**
 * （由 {@link GroupChatRunner} 按 alreadySpoken 过滤, 这里只给候选集）。
 *
 * <p>收敛路径因此是确定的: 轮次 1 全员答 → 轮次 2 起候选全被过滤光 → 空名单 → 停.
 *
 * <p>白名单可限定只让某些 agent 接（角色不同的群, 例如只让"分析师"回话）;
 * 名单为空表示全员参与.
 */
public final class BroadcastPolicy implements ResponsePolicy {

    private final Set<String> whitelist;

    public BroadcastPolicy() {
        this(Collections.<String>emptySet());
    }

    public BroadcastPolicy(String... whitelist) {
        this(new LinkedHashSet<String>(Arrays.asList(whitelist)));
    }

    public BroadcastPolicy(Set<String> whitelist) {
        this.whitelist = whitelist == null ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(whitelist));
    }

    @Override
    public List<String> selectResponders(GroupChatContext context) {
        List<String> out = new ArrayList<String>();
        for (Participant p : context.agents()) {
            if (!whitelist.isEmpty() && !whitelist.contains(p.getId())) {
                continue;
            }
            if (context.getAlreadySpoken().contains(p.getId())) {
                continue;
            }
            // 自己的话不接自己的茬
            if (context.getLatest() != null && p.getId().equals(context.getLatest().getSenderId())) {
                continue;
            }
            out.add(p.getId());
        }
        return out;
    }

    public Set<String> getWhitelist() {
        return whitelist;
    }
}
