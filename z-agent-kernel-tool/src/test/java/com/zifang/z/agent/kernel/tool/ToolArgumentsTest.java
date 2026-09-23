package com.zifang.z.agent.kernel.tool;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * ToolArguments 类型规范化与兜底取参测试.
 *
 * <p>规范化规则与 z-opc z-agent-engine JsonObject 版保持一致:
 * 纯数字串→Long, "true"/"false"→Boolean, "007"/带空白/超 long 不动。
 */
public class ToolArgumentsTest {

    @Test
    public void of规范化数字与布尔字面量字符串() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("task_id", "300");
        raw.put("flag", "true");
        raw.put("off", "false");
        ToolArguments args = ToolArguments.of(raw);

        assertEquals(300L, args.get("task_id"));
        assertEquals(Boolean.TRUE, args.get("flag"));
        assertEquals(Boolean.FALSE, args.get("off"));
        assertEquals(300, args.getInt("task_id", 0));
        assertTrue(args.getBoolean("flag", false));
        assertFalse(args.getBoolean("off", true));
    }

    @Test
    public void of保留非规范整数字符串() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("padded", "007");
        raw.put("blank", "  12");
        raw.put("negative", "-5");
        raw.put("overflow", "99999999999999999999999");
        raw.put("empty", "");
        ToolArguments args = ToolArguments.of(raw);

        assertEquals("007", args.get("padded"));
        assertEquals("  12", args.get("blank"));
        assertEquals("-5", args.get("negative"));
        assertEquals("99999999999999999999999", args.get("overflow"));
        assertEquals("", args.get("empty"));
        // getInt 走 coerceLong 仍能解析负数字符串
        assertEquals(-5, args.getInt("negative", 0));
        assertEquals(0, args.getInt("overflow", 0));
    }

    @Test
    public void getString对Number与Boolean兜底成字符串() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("n", 42);
        raw.put("d", 3.5);
        raw.put("b", Boolean.TRUE);
        raw.put("s", "text");
        ToolArguments args = ToolArguments.of(raw);

        assertEquals("42", args.getString("n"));
        assertEquals("3.5", args.getString("d"));
        assertEquals("true", args.getString("b"));
        assertEquals("text", args.getString("s"));
        assertNull(args.getString("missing"));
        assertEquals("def", args.getString("missing", "def"));
    }

    @Test
    public void getLong与getInt默认值() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("lng", "12345678901");
        raw.put("dbl", 2.0);
        ToolArguments args = ToolArguments.of(raw);

        assertEquals(12345678901L, args.getLong("lng", 0L));
        assertEquals(2L, args.getLong("dbl", 9L));
        assertEquals(2, args.getInt("dbl", 9));
        assertEquals(7L, args.getLong("missing", 7L));
        assertEquals(7, args.getInt("missing", 7));
    }

    @Test
    public void getBoolean数字与字符串语义() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("one", 1);
        raw.put("zero", 0);
        raw.put("yes", "TRUE");
        raw.put("no", "0");
        ToolArguments args = ToolArguments.of(raw);

        assertTrue(args.getBoolean("one", false));
        assertFalse(args.getBoolean("zero", true));
        assertTrue(args.getBoolean("yes", false));
        assertFalse(args.getBoolean("no", true));
        assertTrue(args.getBoolean("missing", true));
    }

    @Test
    public void of递归规范化嵌套Map与List() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("id", "88");
        Map<String, Object> raw = new HashMap<>();
        raw.put("child", nested);
        raw.put("items", new ArrayList<>(Arrays.asList("5", "abc", "true")));
        ToolArguments args = ToolArguments.of(raw);

        Map<String, Object> child = (Map<String, Object>) args.get("child");
        assertEquals(88L, child.get("id"));
        List<?> items = (List<?>) args.get("items");
        assertEquals(5L, items.get(0));
        assertEquals("abc", items.get(1));
        assertEquals(Boolean.TRUE, items.get(2));
    }

    @Test
    public void ofNull与empty() {
        ToolArguments args = ToolArguments.of(null);
        assertTrue(args.isEmpty());
        assertEquals(0, args.size());
        assertFalse(args.containsKey("x"));
        assertNull(args.get("x"));

        ToolArguments empty = ToolArguments.empty();
        assertTrue(empty.isEmpty());
        assertSame(LinkedHashMap.class, empty.toMap().getClass());
    }

    @Test
    public void toMap返回原实例且与BaseTool联通() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("text", "hi");
        ToolArguments args = ToolArguments.of(raw);
        assertSame(raw, args.toMap());

        Tool tool = new BaseTool("echo", "echo tool") {
            @Override
            protected ToolResult doExecute(Map<String, Object> arguments) {
                return ToolResult.text(ToolArguments.of(arguments).getString("text", ""));
            }
        };
        ToolResult r = tool.execute(raw);
        assertFalse(r.isError());
        assertEquals("hi", r.getContent());
        assertNull(r.getCallId());
        assertNull(r.getName());
    }

    @Test
    public void baseTool空参数兜底与schema默认值() {
        Tool tool = new BaseTool("noop", "no-op") {
            @Override
            protected ToolResult doExecute(Map<String, Object> arguments) {
                return ToolResult.text("args=" + arguments.size());
            }
        };
        ToolResult r = tool.execute(null);
        assertEquals("args=0", r.getContent());
        assertEquals("noop", tool.getName());
        assertEquals("no-op", tool.getDescription());
        assertTrue(tool.getSchema().isEmpty());
    }

    @Test
    public void toolResultText与error工厂() {
        ToolResult ok = ToolResult.text("done");
        assertFalse(ok.isError());
        assertEquals("done", ok.getContent());
        assertNull(ok.getMetadata());

        ToolResult bad = ToolResult.error("boom");
        assertTrue(bad.isError());
        assertEquals("boom", bad.getContent());
    }

    @Test
    public void baseTool拒绝空名字() {
        try {
            new BaseTool("", "d") {
                @Override
                protected ToolResult doExecute(Map<String, Object> arguments) {
                    return ToolResult.text("");
                }
            };
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void 不可变List元素规范化不抛异常() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("items", Collections.singletonList("5"));
        ToolArguments args = ToolArguments.of(raw);
        List<?> items = (List<?>) args.get("items");
        assertEquals("5", items.get(0));
    }
}
