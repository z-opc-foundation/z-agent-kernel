package com.zifang.z.agent.kernel.mcp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 用 /bin/cat 当"回声 MCP server": 它把请求行原样写回 stdout,
 * request() 靠 id 匹配拿到响应 — 恰好构成一次最小 JSON-RPC 往返.
 */
public class StdioMcpTransportTest {

    @Test
    public void openHandshakeAndEchoRoundTrip() throws Exception {
        StdioMcpTransport t = new StdioMcpTransport(Arrays.asList("/bin/cat"));
        assertFalse(t.isOpen());
        t.open();
        assertTrue(t.isOpen());

        String resp = t.request("{\"jsonrpc\":\"2.0\",\"id\":777,\"method\":\"tools/list\",\"params\":{}}");
        assertTrue("echo should contain our id", resp.contains("\"id\":777"));
        assertTrue(resp.contains("tools/list"));

        String resp2 = t.request("{\"jsonrpc\":\"2.0\",\"id\":888,\"method\":\"tools/call\",\"params\":{}}");
        assertTrue(resp2.contains("\"id\":888"));

        t.close();
        assertFalse(t.isOpen());
    }

    @Test
    public void requestBeforeOpenFails() {
        StdioMcpTransport t = new StdioMcpTransport(Collections.singletonList("/bin/cat"));
        try {
            t.request("{\"jsonrpc\":\"2.0\",\"id\":1}");
            org.junit.Assert.fail("should throw");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("not open"));
        }
    }

    @Test
    public void emptyCommandRejected() {
        try {
            new StdioMcpTransport(Collections.<String>emptyList());
            org.junit.Assert.fail("should throw");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }
}
