package com.zifang.z.agent.kernel.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP Client SPI — Model Context Protocol 客户端.
 *
 * <p>实现方: z-mcp (MCP 注册中心 + 桥接).
 *
 * <p>职责: 跟上游 MCP server (stdio / WebSocket / SSE) 建立连接, 列出可用工具, 转发工具调用.
 */
public interface McpClient {

    /**
     * @return client 唯一标识
     */
    String name();

    /**
     * 跟 server 建连(可能耗时, 用于 lazy 初始化).
     */
    void connect();

    /**
     * 列 server 注册的工具(JSON Schema 形式).
     */
    List<McpTool> listTools();

    /**
     * 调一个工具.
     *
     * @param toolName  工具名
     * @param arguments 工具入参(JSON)
     * @return 工具执行结果(文本或结构化)
     */
    McpResult call(String toolName, Map<String, Object> arguments);

    /**
     * 断连.
     */
    void disconnect();

    /**
     * @return 当前是否已连接
     */
    boolean isConnected();

    /**
     * MCP 工具描述.
     */
    final class McpTool {
        public final String name;
        public final String description;
        public final Map<String, Object> inputSchema;

        public McpTool(String name, String description, Map<String, Object> inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
    }

    /**
     * MCP 工具调用结果.
     */
    final class McpResult {
        public final boolean isError;
        public final Object content;
        public final String errorMessage;

        private McpResult(boolean isError, Object content, String errorMessage) {
            this.isError = isError;
            this.content = content;
            this.errorMessage = errorMessage;
        }

        public static McpResult ok(Object content) {
            return new McpResult(false, content, null);
        }

        public static McpResult error(String message) {
            return new McpResult(true, null, message);
        }
    }
}