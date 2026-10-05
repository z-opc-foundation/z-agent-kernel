package com.zifang.z.agent.kernel.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StdioMcpTransport} 的请求超时语义。
 * <p>
 * 病灶：原实现是
 * <pre>
 * long deadline = now + 30_000;
 * while (now &lt; deadline) {
 *     String line = stdout.readLine();   // ← 阻塞点
 *     ...
 * }
 * throw new TimeoutException(...);
 * </pre>
 * MCP server 活着但不回话时，{@code readLine()} 一直阻塞，while 条件没机会重新求值，
 * 30s 超时永远不会触发；又因为方法是 synchronized，一次挂死就把该 transport 永久锁死。
 * 本机实测（deadline=2000ms，server 起 sleep 3600 不写数据）：方法卡在 readLine 不返回。
 */
class StdioMcpTransportTimeoutTest {

    /**
     * server 回话时，应当正常拿到对应 id 的响应。
     * <p>
     * 用 {@code /bin/cat} 当回声 server：它把请求行原样写回 stdout，
     * 所以任何 id 都能匹配上（包括 open() 里的 initialize 握手）。
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void responseIsReturnedWhenServerAnswers() throws Exception {
        StdioMcpTransport t = new StdioMcpTransport(
                java.util.Collections.singletonList("/bin/cat"));
        try {
            t.open();
            assertTrue(t.isOpen(), "open() 的 initialize 握手应完成");
            String resp = t.request(
                    "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/list\",\"params\":{}}");
            assertTrue(resp.contains("\"id\":42"), "应拿到 id=42 的响应，实际: " + resp);
            assertTrue(resp.contains("tools/list"), "响应内容应完整，实际: " + resp);
        } finally {
            t.close();
        }
    }

    /**
     * 核心闸门：server 不回话时，必须在 timeout 内抛 TimeoutException。
     * <p>
     * 注意 server 必须<b>回 initialize</b>（否则 open() 自己就先超时了），
     * 但对后续请求不回话 —— 这才是"server 起来后卡住"的真实场景。
     * 旧实现下这条会卡在 readLine 上直到 JUnit 超时（进程流读取不响应线程中断）。
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void serverThatNeverAnswersTimesOut() throws Exception {
        // 第一次读（initialize）原样回显，之后的请求一律不回
        StdioMcpTransport t = new StdioMcpTransport(java.util.Arrays.asList(
                "sh", "-c",
                "IFS= read -r first; echo \"$first\"; while IFS= read -r line; do :; done"));
        try {
            t.open();
            assertTrue(t.isOpen(), "initialize 握手应完成");

            long t0 = System.currentTimeMillis();
            java.util.concurrent.TimeoutException ex = assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> t.request("{\"jsonrpc\":\"2.0\",\"id\":999,\"method\":\"tools/list\"}"));
            long elapsed = System.currentTimeMillis() - t0;

            assertTrue(ex.getMessage().contains("timeout"),
                    "报错应说明超时，实际: " + ex.getMessage());
            assertTrue(elapsed <= 35_000L,
                    "应在 30s 附近超时，实际耗时 " + elapsed + "ms");
        } finally {
            t.close();
        }
    }

    /**
     * server 回完 initialize 就退出：后续请求要么被 isOpen() 拦下，要么报 EOF/超时，
     * 关键是<b>不会挂死</b>。
     * <p>
     * 不断言 {@code isOpen()} 的即时取值 —— open() 刚返回时子进程可能还没真正退出，
     * 那是 OS 调度层面的竞态，与本用例要验证的点无关。
     */
    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void serverDyingAfterHandshakeDoesNotHang() throws Exception {
        StdioMcpTransport t = new StdioMcpTransport(java.util.Arrays.asList(
                "sh", "-c", "IFS= read -r first; echo \"$first\"; exit 0"));
        try {
            t.open();
            long t0 = System.currentTimeMillis();
            assertThrows(Exception.class,
                    () -> t.request("{\"jsonrpc\":\"2.0\",\"id\":999,\"method\":\"tools/list\"}"),
                    "向已退出的 server 发请求必须抛异常，而不是挂死");
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(elapsed <= 35_000L,
                    "server 已死时应快速失败（EOF 或超时），不应挂死，实际耗时 " + elapsed + "ms");
        } finally {
            t.close();
        }
    }

    /** 缺 id 的请求要当场报错，不该发出去。 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void requestWithoutIdIsRejected() throws Exception {
        StdioMcpTransport t = new StdioMcpTransport(
                java.util.Collections.singletonList("/bin/cat"));
        try {
            t.open();
            assertThrows(IllegalArgumentException.class,
                    () -> t.request("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}"));
        } finally {
            t.close();
        }
    }

    /** 未 open 就发请求要报 IllegalState。 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void requestBeforeOpenRejected() {
        StdioMcpTransport t = new StdioMcpTransport(
                java.util.Collections.singletonList("sh"));
        assertThrows(IllegalStateException.class,
                () -> t.request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}"));
    }

    /** 空命令要当场拒绝。 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void emptyCommandRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StdioMcpTransport(java.util.Collections.<String>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new StdioMcpTransport(null));
    }
}
