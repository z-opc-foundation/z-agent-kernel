package com.zifang.z.agent.kernel.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行参数 — 包装 Map 并提供类型化访问器.
 *
 * <p>LLM 生成的 tool_call 参数经常把数字/布尔写成字符串 (如 {@code "task_id":"300"})，
 * 也常把本应是字符串的值写成数字；裸 Map 访问不做任何类型兜底，会导致工具侧静默取参失败。
 * 因此 {@link #of(Map)} 入口做一次规范化：整型/布尔字面量字符串转成对应类型；
 * {@link #getString(String)} 里把 Number/Boolean 兜底成字符串。
 * 规范化规则与 z-opc z-agent-engine 的 JsonObject 版 ToolArguments 保持一致。
 */
public final class ToolArguments {

    private final Map<String, Object> data;

    private ToolArguments(Map<String, Object> data) {
        this.data = data != null ? data : new LinkedHashMap<String, Object>();
    }

    public static ToolArguments empty() {
        return new ToolArguments(new LinkedHashMap<String, Object>());
    }

    /**
     * 原地规范化入参 Map 并包装。注意: 与调用方共享同一 Map 实例。
     */
    public static ToolArguments of(Map<String, Object> data) {
        if (data != null) {
            normalizeMap(data);
        }
        return new ToolArguments(data);
    }

    private static void normalizeMap(Map<String, Object> o) {
        if (o == null) {
            return;
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<Map.Entry<String, Object>>(o.entrySet());
        for (Map.Entry<String, Object> en : entries) {
            o.put(en.getKey(), normalizeValue(en.getValue()));
        }
    }

    private static Object normalizeValue(Object v) {
        if (v instanceof String) {
            String s = (String) v;
            if (!s.isEmpty()) {
                if ("true".equals(s)) {
                    return Boolean.TRUE;
                }
                if ("false".equals(s)) {
                    return Boolean.FALSE;
                }
                if (isCanonicalDecimalInteger(s)) {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException ignore) {
                        // 超出 long 范围则保持字符串
                    }
                }
            }
            return v;
        }
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            normalizeMap(m);
            return v;
        }
        if (v instanceof List) {
            List<?> arr = (List<?>) v;
            for (int i = 0; i < arr.size(); i++) {
                Object normalized = normalizeValue(arr.get(i));
                if (normalized != arr.get(i)) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Object> mutable = (List<Object>) arr;
                        mutable.set(i, normalized);
                    } catch (UnsupportedOperationException ignore) {
                        // 不可变 List 保持原样
                    }
                }
            }
            return v;
        }
        return v;
    }

    /**
     * 仅转换"规范十进制整数字符串"（纯数字、无前导零），避免破坏 "007"、带空白、超 long 范围等语义。
     */
    private static boolean isCanonicalDecimalInteger(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !(s.length() > 1 && s.charAt(0) == '0');
    }

    private static Long coerceLong(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String) {
            try {
                return Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    public String getString(String key) {
        Object v = data.get(key);
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return v instanceof String ? (String) v : null;
    }

    public String getString(String key, String defaultValue) {
        String v = getString(key);
        return v != null ? v : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Long coerced = coerceLong(data.get(key));
        if (coerced != null) {
            return coerced.intValue();
        }
        return defaultValue;
    }

    public long getLong(String key, long defaultValue) {
        Long coerced = coerceLong(data.get(key));
        return coerced != null ? coerced : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object raw = data.get(key);
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue() != 0d;
        }
        if (raw instanceof String) {
            String s = (String) raw;
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
                return false;
            }
        }
        return defaultValue;
    }

    public Object get(String key) {
        return data.get(key);
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    public int size() {
        return data.size();
    }

    public Map<String, Object> toMap() {
        return data;
    }

    @Override
    public String toString() {
        return "ToolArguments" + data;
    }
}
