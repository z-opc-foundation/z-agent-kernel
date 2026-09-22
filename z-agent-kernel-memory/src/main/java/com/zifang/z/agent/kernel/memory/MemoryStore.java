package com.zifang.z.agent.kernel.memory;

import com.zifang.z.agent.kernel.message.Msg;

import java.util.List;
import java.util.Optional;

/**
 * 记忆存储 SPI. 上层 agent 用它存对话历史 / 用户偏好 / 事实知识.
 *
 * <p>save/load/search/delete: 同步 API.
 * <p>scope: 存储作用域 (user id / session id / agent id).
 */
public interface MemoryStore<T extends MemoryItem> {

    String getScope();

    void save(T item);

    Optional<T> load(String id);

    List<T> search(String query, int limit);

    void delete(String id);

    default List<Msg> listRecentMessages(int limit) {
        return java.util.Collections.emptyList();
    }
}