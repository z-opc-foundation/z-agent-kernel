package com.zifang.z.agent.kernel.tool;

import java.util.Map;

/**
 * 工具调用 SPI. LLM 通过工具 schema 知道工具能干什么, 通过 execute 调用.
 *
 * <p>getName/getDescription/getSchema: 给 LLM 看的元信息 + JSON Schema (OpenAI tools 格式).
 * <p>execute: 同步执行, 返回 ToolResult.
 */
public interface Tool {

    String getName();

    String getDescription();

    Map<String, Object> getSchema();

    ToolResult execute(Map<String, Object> arguments);
}