package com.zifang.z.agent.kernel.state;

/**
 * 状态管理器 SPI. 上层 agent 用它在 run 开始时建新 State, 在 checkpoint / resume 时复原.
 *
 * <p>newState: 创建新作用域.
 * <p>snapshot: 把 State 序列化成可持久化对象.
 * <p>restore: 从快照还原 State.
 */
public interface StateManager {

    State newState(String scope);

    StateSnapshot snapshot(State state);

    State restore(StateSnapshot snapshot);
}