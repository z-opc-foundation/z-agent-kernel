package com.zifang.z.agent.kernel.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio 传输 — 把 MCP server 当子进程拉起, newline-delimited JSON-RPC over stdin/stdout.
 *
 * <p>启动即发 {@code initialize} 握手 (协议版本 + client info), 收到 server 的 result 才算 open.
 * stderr 全量丢弃 (server 的日志), 防管道阻塞.
 */
public final class StdioMcpTransport implements McpTransport {

    private final ProcessBuilder command;
    private final AtomicLong nextId = new AtomicLong();
    private Process process;
    private BufferedReader stdout;
    private OutputStream stdin;

    public StdioMcpTransport(java.util.List<String> commandWithArgs) {
        if (commandWithArgs == null || commandWithArgs.isEmpty()) {
            throw new IllegalArgumentException("mcp stdio command is empty");
        }
        this.command = new ProcessBuilder(commandWithArgs);
        this.command.redirectErrorStream(false);
    }

    @Override
    public synchronized void open() throws Exception {
        if (isOpen()) {
            return;
        }
        process = command.start();
        stdin = process.getOutputStream();
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread errDrainer = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader err = new BufferedReader(
                            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
                    while (err.readLine() != null) {
                        // 丢弃 server 日志, 防管道阻塞
                    }
                } catch (IOException ignored) {
                }
            }
        }, "mcp-stderr-drain");
        errDrainer.setDaemon(true);
        errDrainer.start();

        // initialize 握手
        String init = "{\"jsonrpc\":\"2.0\",\"id\":" + nextId.incrementAndGet()
                + ",\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2024-11-05\","
                + "\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"z-agent-kernel\",\"version\":\"0.2.0\"}}}";
        request(init);
        // notifications/initialized
        sendLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    @Override
    public synchronized String request(String requestJson) throws Exception {
        if (!isOpen()) {
            throw new IllegalStateException("transport not open");
        }
        long id = extractId(requestJson);
        sendLine(requestJson);
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            String line = stdout.readLine();
            if (line == null) {
                throw new IOException("mcp server closed stdout");
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.contains("\"id\":" + id)) {
                continue; // notification 或别的响应
            }
            return trimmed;
        }
        throw new java.util.concurrent.TimeoutException("mcp request timeout (30s)");
    }

    private long extractId(String json) {
        int i = json.indexOf("\"id\":");
        if (i < 0) {
            throw new IllegalArgumentException("request json has no id: " + json);
        }
        int s = i + 5;
        while (s < json.length() && json.charAt(s) == ' ') {
            s++;
        }
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)))) {
            e++;
        }
        return Long.parseLong(json.substring(s, e));
    }

    private void sendLine(String json) throws IOException {
        stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    @Override
    public synchronized void close() {
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process = null;
            stdout = null;
            stdin = null;
        }
    }

    @Override
    public synchronized boolean isOpen() {
        return process != null && process.isAlive();
    }
}
