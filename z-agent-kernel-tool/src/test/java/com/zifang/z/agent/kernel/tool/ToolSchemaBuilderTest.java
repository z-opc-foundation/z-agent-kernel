package com.zifang.z.agent.kernel.tool;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ToolSchemaBuilder required 语义测试: 既有方法默认 required=true (0.1.0 行为不变),
 * 新增 boolean 重载支持非必填参数 (对齐 z-opc 老 ToolSchemaBuilder 默认非必填的语义).
 */
public class ToolSchemaBuilderTest {

    @Test
    @SuppressWarnings("unchecked")
    public void 默认方法保持required为真() {
        Map<String, Object> schema = new ToolSchemaBuilder()
                .string("a", "desc a")
                .stringArray("b", "desc b")
                .object("c", "desc c")
                .build();
        List<String> required = (List<String>) schema.get("required");
        assertEquals(Arrays.asList("a", "b", "c"), required);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void required重载支持非必填() {
        Map<String, Object> schema = new ToolSchemaBuilder()
                .string("a", "desc a", false)
                .stringArray("b", "desc b", false)
                .array("c", "desc c", null, false)
                .array("d", "desc d", "string", true)
                .object("e", "desc e", false)
                .bool("f", "desc f", false)
                .integer("g", "desc g", false)
                .number("h", "desc h", false)
                .build();
        assertFalse(((Map<String, Object>) schema.get("properties")).get("a") instanceof List);
        assertTrue(((Map<String, Object>) schema.get("properties")).containsKey("a"));
        List<String> required = (List<String>) schema.get("required");
        assertEquals(Arrays.asList("d"), required);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void 数组缺items时保持type信息() {
        Map<String, Object> schema = new ToolSchemaBuilder()
                .array("tags", "标签列表", null, false)
                .build();
        Map<String, Object> tags = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("tags");
        assertEquals("array", tags.get("type"));
        assertFalse(tags.containsKey("items"));
    }
}
