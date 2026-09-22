package com.zifang.z.agent.kernel.state;

import java.util.Map;

/**
 * 状态接口. agent 运行时键值存储 (短期变量 + 工作记忆).
 *
 * <p>get/set/delete/snapshot: 同步 API.
 * <p>attributes: 状态所属作用域 (run id / session id).
 */
public interface State {

    String getScope();

    <T> T get(String key);

    void set(String key, Object value);

    void delete(String key);

    Map<String, Object> snapshot();
}