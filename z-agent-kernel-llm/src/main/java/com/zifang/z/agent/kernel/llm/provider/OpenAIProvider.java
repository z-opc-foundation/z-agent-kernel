package com.zifang.z.agent.kernel.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.llm.support.LlmException;
import com.zifang.z.agent.kernel.llm.support.LlmHttp;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import okhttp3.Headers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI Chat Completions provider. 也作为 DeepSeek / Qwen 等 OpenAI 兼容协议的基类参考.
 *
 * <p>默认 base URL: https://api.openai.com/v1
 */
public class OpenAIProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String apiKey;
    private final String apiBase;
    private final LlmHttp http;

    public OpenAIProvider(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public OpenAIProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase == null || apiBase.isEmpty() ? DEFAULT_BASE_URL : apiBase;
        this.http = new LlmHttp();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public List<Model> listModels() {
        List<Model> out = new ArrayList<>();
        out.add(new Model("gpt-4o", "GPT-4o", "openai",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION, Model.Capability.JSON_MODE),
                128000L, 16384L));
        out.add(new Model("gpt-4o-mini", "GPT-4o mini", "openai",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION, Model.Capability.JSON_MODE),
                128000L, 16384L));
        out.add(new Model("o1", "o1", "openai",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS),
                200000L, 100000L));
        out.add(new Model("o1-mini", "o1-mini", "openai",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS),
                128000L, 65536L));
        out.add(new Model("o3-mini", "o3-mini", "openai",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS),
                200000L, 100000L));
        out.add(new Model("gpt-4-turbo", "GPT-4 Turbo", "openai",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION),
                128000L, 4096L));
        return out;
    }

    @Override
    public boolean supportsModel(String modelId) {
        if (modelId == null) return false;
        String m = modelId.toLowerCase();
        return m.startsWith("gpt-") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.startsWith("chatgpt-");
    }

    @Override
    public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
        Map<String, Object> body = buildRequestBody(request, false);
        String raw = http.postJson(apiBase + "/chat/completions", authHeaders(), body);
        return parseResponse(raw);
    }

    @Override
    public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk, Consumer<Throwable> onError) {
        // TODO: 实现 SSE 流式解析. 当前先抛异常, 让上层感知暂未实现.
        onError.accept(new LlmException(name(), "streamChat not yet implemented in OpenAIProvider"));
    }

    // ---- 内部 helper ----

    protected Headers authHeaders() {
        return new Headers.Builder()
                .add("Authorization", "Bearer " + apiKey)
                .add("Content-Type", "application/json")
                .build();
    }

    protected Map<String, Object> buildRequestBody(ChatCompletionsRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("messages", toOpenAIMessages(request.getMessages()));
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            body.put("tools", toOpenAITools(request.getTools()));
        }
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP() != null) body.put("top_p", request.getTopP());
        if (request.getMaxTokens() != null) body.put("max_tokens", request.getMaxTokens());
        if (stream) body.put("stream", true);
        if (request.getProviderParams() != null && !request.getProviderParams().isEmpty()) {
            body.putAll(request.getProviderParams());
        }
        return body;
    }

    protected List<Map<String, Object>> toOpenAIMessages(List<Msg> msgs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Msg m : msgs) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("role", toOpenAIRole(m.getRole()));
            if (m.getName() != null) om.put("name", m.getName());
            if (m.getRole() == MessageRole.TOOL) {
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("tool_call_id", m.getToolCallId() == null ? "" : m.getToolCallId());
                tc.put("content", m.getContent() == null ? "" : m.getContent());
                om.putAll(tc);
            } else if (!m.getToolCalls().isEmpty()) {
                List<Map<String, Object>> tcs = new ArrayList<>();
                for (ToolCall tc : m.getToolCalls()) {
                    Map<String, Object> tcm = new LinkedHashMap<>();
                    tcm.put("id", tc.getId());
                    tcm.put("type", "function");
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.getName());
                    fn.put("arguments", tc.getArgumentsJson() == null ? "{}" : tc.getArgumentsJson());
                    tcm.put("function", fn);
                    tcs.add(tcm);
                }
                om.put("tool_calls", tcs);
                if (m.getContent() != null && !m.getContent().isEmpty()) {
                    om.put("content", m.getContent());
                }
            } else {
                om.put("content", m.getContent() == null ? "" : m.getContent());
            }
            out.add(om);
        }
        return out;
    }

    protected String toOpenAIRole(MessageRole role) {
        switch (role) {
            case SYSTEM: return "system";
            case USER: return "user";
            case ASSISTANT: return "assistant";
            case TOOL: return "tool";
            case FUNCTION: return "function";
            default: return "user";
        }
    }

    protected List<Map<String, Object>> toOpenAITools(List<com.zifang.z.agent.kernel.tool.Tool> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.zifang.z.agent.kernel.tool.Tool t : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.getName());
            fn.put("description", t.getDescription() == null ? "" : t.getDescription());
            if (t.getSchema() != null) {
                fn.put("parameters", t.getSchema());
            } else {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("type", "object");
                empty.put("properties", Collections.emptyMap());
                fn.put("parameters", empty);
            }
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("type", "function");
            wrap.put("function", fn);
            out.add(wrap);
        }
        return out;
    }

    protected ChatCompletionsResponse parseResponse(String raw) {
        try {
            JsonNode root = http.json().readTree(raw);
            String id = textOrNull(root, "id");
            String model = textOrNull(root, "model");
            String finish = textOrNull(root.path("choices").path(0), "finish_reason");
            List<ChatCompletionsResponse.Choice> choices = new ArrayList<>();
            JsonNode arr = root.path("choices");
            for (int i = 0; i < arr.size(); i++) {
                JsonNode c = arr.get(i);
                int idx = c.path("index").asInt(i);
                JsonNode msg = c.path("message");
                String content = textOrNull(msg, "content");
                List<ToolCall> tcs = new ArrayList<>();
                JsonNode tcArr = msg.path("tool_calls");
                if (tcArr.isArray()) {
                    for (JsonNode t : tcArr) {
                        JsonNode fn = t.path("function");
                        tcs.add(new ToolCall(textOrNull(t, "id"), textOrNull(fn, "name"), textOrNull(fn, "arguments")));
                    }
                }
                choices.add(new ChatCompletionsResponse.Choice(idx, content, tcs, textOrNull(c, "finish_reason")));
            }
            JsonNode u = root.path("usage");
            TokenUsage usage = new TokenUsage(
                    u.path("prompt_tokens").asLong(0L),
                    u.path("completion_tokens").asLong(0L),
                    u.path("total_tokens").asLong(0L));
            return new ChatCompletionsResponse(id, model, choices, usage, finish, Collections.emptyMap());
        } catch (Exception e) {
            throw new LlmException(name(), "parseResponse failed: " + raw, e);
        }
    }

    protected static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}