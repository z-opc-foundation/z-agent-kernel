package com.zifang.z.agent.kernel.agent;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.TokenUsage;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 调用响应 DTO.
 *
 * <p>output: agent 最终输出 (USER-facing 一条 ASSISTANT Msg).
 * <p>steps: agent 推理步骤轨迹 (ReAct Thought/Action/Observation, Plan agent 各 step).
 * <p>usage: 累计 token 用量 (供 observability 计费).
 * <p>finishedReason: 完成原因 ("completed" / "max_steps" / "tool_error" / "timeout").
 * <p>error: 失败时的异常 (成功时 null).
 */
public final class AgentResponse {

    private final Msg output;
    private final List<Step> steps;
    private final TokenUsage usage;
    private final String finishedReason;
    private final Throwable error;
    private final Map<String, Object> metadata;

    public AgentResponse(Msg output, List<Step> steps, TokenUsage usage,
                         String finishedReason, Throwable error, Map<String, Object> metadata) {
        this.output = output;
        this.steps = steps == null ? Collections.emptyList() : Collections.unmodifiableList(steps);
        this.usage = usage == null ? TokenUsage.empty() : usage;
        this.finishedReason = finishedReason;
        this.error = error;
        this.metadata = metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
    }

    public Msg getOutput() {
        return output;
    }

    public List<Step> getSteps() {
        return steps;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public String getFinishedReason() {
        return finishedReason;
    }

    public Throwable getError() {
        return error;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public static final class Step {

        private final int index;
        private final String kind;
        private final Msg thought;
        private final Msg action;
        private final Msg observation;
        private final long durationMs;

        public Step(int index, String kind, Msg thought, Msg action, Msg observation, long durationMs) {
            this.index = index;
            this.kind = kind;
            this.thought = thought;
            this.action = action;
            this.observation = observation;
            this.durationMs = durationMs;
        }

        public int getIndex() {
            return index;
        }

        public String getKind() {
            return kind;
        }

        public Msg getThought() {
            return thought;
        }

        public Msg getAction() {
            return action;
        }

        public Msg getObservation() {
            return observation;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }
}