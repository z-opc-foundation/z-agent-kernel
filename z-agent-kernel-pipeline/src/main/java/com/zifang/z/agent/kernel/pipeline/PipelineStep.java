package com.zifang.z.agent.kernel.pipeline;

import java.util.Map;

/**
 * 工作流步骤 SPI. pipeline 由一组步骤串联, 每个步骤可前置条件 + 后置副作用.
 *
 * <p>execute: 同步执行, 返回 StepResult 决定下一步走向.
 */
public interface PipelineStep {

    String getName();

    StepResult execute(PipelineContext context);

    interface StepResult {
        Status getStatus();

        Object getOutput();

        String getNextStepName();

        enum Status {
            CONTINUE,
            NEXT,
            SKIP,
            FAIL
        }
    }

    interface PipelineContext {
        String getPipelineName();

        String getCurrentStepName();

        Map<String, Object> getVariables();

        <T> T getVariable(String key);

        void setVariable(String key, Object value);

        void fail(Throwable cause);
    }
}