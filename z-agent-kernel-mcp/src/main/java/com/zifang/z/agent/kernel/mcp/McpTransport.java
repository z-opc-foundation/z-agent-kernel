package com.zifang.z.agent.kernel.mcp;

/**
 * MCP 传输层 SPI — 一条到 MCP server 的 JSON-RPC 通道.
 *
 * <p>实现方: stdio 子进程 (默认) / HTTP+SSE / WebSocket. 与 {@link McpClient} 的分工:
 * Transport 只管"发请求拿响应"的字节搬运, Client 负责协议 (initialize / tools/list / tools/call)
 * 与生命周期.
 */
public interface McpTransport {

    void open() throws Exception;

    /**
     * 发一个 JSON-RPC 请求并等响应.
     *
     * @param requestJson 完整请求 JSON (含 id)
     * @return 响应 JSON 文本
     */
    String request(String requestJson) throws Exception;

    void close() throws Exception;

    boolean isOpen();
}
