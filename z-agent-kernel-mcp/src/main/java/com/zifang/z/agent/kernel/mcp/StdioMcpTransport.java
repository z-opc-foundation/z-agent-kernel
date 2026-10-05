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

    /** 单个请求的等待上限。 */
    private static final long REQUEST_TIMEOUT_MS = 30_000L;

    /** 读线程结束时投递的标记：server 关掉了 stdout。 */
    private static final Object EOF_MARKER = new Object();

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

    /**
     * 发送一个请求并等响应。
     * <p>
     * <b>原来的实现把 {@code stdout.readLine()} 放在 while 条件的循环体里，
     * 于是 {@code deadline} 形同虚设</b>：MCP server 活着但不回话时，readLine() 会一直阻塞，
     * while 条件根本得不到重新求值，30s 超时永远不会触发。本机实测：
     * deadline 设 2000ms、server 起 {@code sleep 3600} 不写任何数据，
     * 方法一直卡在 readLine 上不返回。
     * 叠加上本方法是 {@code synchronized} —— 一次挂死就把这个 transport 永久锁死，
     * 后续所有请求都排队等 monitor，再也发不出去。
     * <p>
     * 现在改成：读 stdout 交给一个一次性 daemon 线程，把结果投递到
     * {@link BlockingQueue}；主线程用 {@code poll(剩余时间)} 真正等待。
     * server 不回话时 poll 到期即超时返回，不会被读操作拖住。
     */
    @Override
    public synchronized String request(String requestJson) throws Exception {
        if (!isOpen()) {
            throw new IllegalStateException("transport not open");
        }
        long id = extractId(requestJson);
        sendLine(requestJson);

        final long timeoutMs = REQUEST_TIMEOUT_MS;
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;

        // 读操作搬到独立线程：它可以一直等，主线程的 poll 才是超时的真正执行者
        final java.util.concurrent.BlockingQueue<Object> q =
                new java.util.concurrent.LinkedBlockingQueue<>();
        final BufferedReader reader = this.stdout;
        Thread readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || !trimmed.contains("\"id\":" + id)) {
                        continue; // notification 或别的响应
                    }
                    q.offer(trimmed);
                    return;
                }
                q.offer(EOF_MARKER);
            } catch (IOException e) {
                q.offer(e);
            }
        }, "zagent-mcp-read-" + id);
        readerThread.setDaemon(true);
        readerThread.start();

        long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
        if (remainingMs < 1) {
            remainingMs = 1;
        }
        Object result = q.poll(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (result == null) {
            throw new java.util.concurrent.TimeoutException(
                    "mcp request timeout (" + timeoutMs + "ms)");
        }
        if (result == EOF_MARKER) {
            throw new IOException("mcp server closed stdout");
        }
        if (result instanceof IOException) {
            throw (IOException) result;
        }
        return (String) result;
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
