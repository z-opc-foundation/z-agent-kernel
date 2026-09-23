package com.zifang.z.agent.kernel.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for OpenAI-compatible JSON Schema (function calling 格式).
 *
 * <p>对应 z-opc 老 ToolSchemaBuilder — 蒸馏到用 Map 输出, 不依赖 z-opc 内部类型.
 *
 * <p>典型用法:
 * <pre>{@code
 * Map<String, Object> schema = new ToolSchemaBuilder()
 *     .string("city", "城市名")
 *     .integer("days", "天数", false)
 *     .build();
 * }</pre>
 */
public class ToolSchemaBuilder {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    public ToolSchemaBuilder string(String name, String description) {
        return string(name, description, true);
    }

    public ToolSchemaBuilder string(String name, String description, boolean required) {
        properties.put(name, jsonSchema("string", description, null, null));
        if (required) this.required.add(name);
        return this;
    }

    public ToolSchemaBuilder stringArray(String name, String description) {
        return array(name, description, "string", true);
    }

    public ToolSchemaBuilder stringArray(String name, String description, boolean required) {
        return array(name, description, "string", required);
    }

    public ToolSchemaBuilder bool(String name, String description) {
        return bool(name, description, true);
    }

    public ToolSchemaBuilder bool(String name, String description, boolean required) {
        properties.put(name, jsonSchema("boolean", description, null, null));
        if (required) this.required.add(name);
        return this;
    }

    public ToolSchemaBuilder integer(String name, String description) {
        return integer(name, description, true);
    }

    public ToolSchemaBuilder integer(String name, String description, boolean required) {
        properties.put(name, jsonSchema("integer", description, null, null));
        if (required) this.required.add(name);
        return this;
    }

    public ToolSchemaBuilder number(String name, String description) {
        return number(name, description, true);
    }

    public ToolSchemaBuilder number(String name, String description, boolean required) {
        properties.put(name, jsonSchema("number", description, null, null));
        if (required) this.required.add(name);
        return this;
    }

    public ToolSchemaBuilder array(String name, String description) {
        return array(name, description, null);
    }

    public ToolSchemaBuilder array(String name, String description, String itemType) {
        return array(name, description, itemType, true);
    }

    public ToolSchemaBuilder array(String name, String description, String itemType, boolean required) {
        Map<String, Object> items = itemType == null ? null : Collections.singletonMap("type", itemType);
        properties.put(name, jsonSchema("array", description, items, null));
        if (required) this.required.add(name);
        return this;
    }

    public ToolSchemaBuilder object(String name, String description) {
        return object(name, description, (Map<String, Object>) null, true);
    }

    public ToolSchemaBuilder object(String name, String description, Map<String, Object> nestedProperties) {
        return object(name, description, nestedProperties, true);
    }

    public ToolSchemaBuilder object(String name, String description, boolean required) {
        return object(name, description, (Map<String, Object>) null, required);
    }

    public ToolSchemaBuilder object(String name, String description, Map<String, Object> nestedProperties,
                                    boolean required) {
        Map<String, Object> objSchema = new LinkedHashMap<>();
        objSchema.put("type", "object");
        if (description != null) objSchema.put("description", description);
        if (nestedProperties != null) {
            objSchema.put("properties", nestedProperties);
        }
        properties.put(name, objSchema);
        if (required) this.required.add(name);
        return this;
    }

    public ToolSchemaBuilder enumString(String name, String description, String... allowed) {
        Map<String, Object> prop = jsonSchema("string", description, null, null);
        prop.put("enum", Arrays.asList(allowed));
        properties.put(name, prop);
        this.required.add(name);
        return this;
    }

    public Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", new ArrayList<>(required));
        }
        return schema;
    }

    private static Map<String, Object> jsonSchema(String type, String description,
                                                  Map<String, Object> items, Map<String, Object> extra) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        if (description != null && !description.isEmpty()) schema.put("description", description);
        if (items != null) schema.put("items", items);
        if (extra != null) schema.putAll(extra);
        return schema;
    }
}