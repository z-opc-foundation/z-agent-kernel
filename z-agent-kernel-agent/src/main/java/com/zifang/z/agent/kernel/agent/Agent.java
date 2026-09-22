package com.zifang.z.agent.kernel.agent;

/**
 * Agent SPI. 上层 (z-agent 平台 / z-bot 本地应用) 调 agent.run() 拿到 AgentResponse.
 *
 * <p>name: agent 名 (用于 dispatch / 调试).
 * <p>run: 同步执行 (可能内部 ReAct 多轮 tool 调用).
 * <p>streamRun: 流式执行, 每个步骤完成回调 onStep (上层可渲染到 UI).
 */
public interface Agent {

    String getName();

    AgentResponse run(AgentRequest request);

    void streamRun(AgentRequest request, java.util.function.Consumer<AgentResponse.Step> onStep,
                   java.util.function.Consumer<AgentResponse> onComplete,
                   java.util.function.Consumer<Throwable> onError);

    void reset();
}