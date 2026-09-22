package com.zifang.z.agent.kernel.pipeline;

import java.util.List;

/**
 * 工作流 SPI. 多 agent / 多步骤编排.
 *
 * <p>execute: 同步跑完整 pipeline, 返回 PipelineResult.
 * <p>getSteps: 步骤列表 (调试 / 可视化用).
 */
public interface Pipeline {

    String getName();

    List<PipelineStep> getSteps();

    PipelineResult execute(PipelineStep.PipelineContext context);

    interface PipelineResult {
        boolean isSuccess();

        Object getFinalOutput();

        List<StepTrace> getStepTraces();

        Throwable getError();

        interface StepTrace {
            String getStepName();

            long getDurationMs();

            PipelineStep.StepResult getResult();
        }
    }
}