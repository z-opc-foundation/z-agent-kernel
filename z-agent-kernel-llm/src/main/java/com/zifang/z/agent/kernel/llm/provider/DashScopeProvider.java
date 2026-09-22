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
 * 阿里云 DashScope 原生 text-generation API provider.
 *
 * <p>默认 base URL: https://dashscope.aliyuncs.com/api/v1
 * <p>协议独立, 不走 OpenAI 兼容层: input.messages + parameters, output.choices[].message.content.
 * <p>适合走 Qwen / 通义系列全部模型 (含 qwen-vl / qwen-audio / qwen-coder 等 OpenAI 兼容模式没暴露的模型).
 */
public class DashScopeProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";

    private final String apiKey;
    private final String apiBase;
    private final LlmHttp http;

    public DashScopeProvider(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public DashScopeProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase == null || apiBase.isEmpty() ? DEFAULT_BASE_URL : apiBase;
        this.http = new LlmHttp();
    }

    @Override
    public String name() {
        return "dashscope";
    }

    @Override
    public List<Model> listModels() {
        List<Model> out = new ArrayList<>();
        out.add(new Model("qwen-max", "Qwen Max", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.JSON_MODE),
                128000L, 8192L));
        out.add(new Model("qwen-max-latest", "Qwen Max Latest", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.JSON_MODE),
                128000L, 8192L));
        out.add(new Model("qwen-plus", "Qwen Plus", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.JSON_MODE),
                128000L, 8192L));
        out.add(new Model("qwen-turbo", "Qwen Turbo", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.JSON_MODE),
                128000L, 8192L));
        out.add(new Model("qwen-coder-plus", "Qwen Coder Plus", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS),
                128000L, 8192L));
        out.add(new Model("qwen-vl-max", "Qwen VL Max", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.VISION),
                32000L, 8192L));
        out.add(new Model("qwen-vl-plus", "Qwen VL Plus", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.VISION),
                32000L, 8192L));
        out.add(new Model("qwen-audio-asr", "Qwen Audio ASR", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.AUDIO),
                32000L, 4096L));
        out.add(new Model("qwen-audio-tts", "Qwen Audio TTS", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.AUDIO),
                32000L, 4096L));
        out.add(new Model("qwen3-max-preview", "Qwen3 Max Preview", "dashscope",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.JSON_MODE),
                256000L, 32768L));
        return out;
    }

    @Override
    public boolean supportsModel(String modelId) {
        if (modelId == null) return false;
        String m = modelId.toLowerCase();
        return m.startsWith("qwen-") || m.startsWith("qwen3-");
    }

    @Override
    public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
        Map<String, Object> body = buildRequestBody(request, false);
        String raw = http.postJson(apiBase + "/services/aigc/text-generation/generation", authHeaders(), body);
        return parseResponse(raw);
    }

    @Override
    public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk, Consumer<Throwable> onError) {
        Map<String, Object> body = buildRequestBody(request, true);
        http.postJsonStream(apiBase + "/services/aigc/text-generation/generation", authHeaders(), body,
                line -> {
                    ChatCompletionsResponse chunk = parseStreamChunk(line);
                    if (chunk != null) onChunk.accept(chunk);
                },
                onError,
                null);
    }

    /**
     * 解析 DashScope 原生 SSE 流式 chunk (incremental_output=true 模式).
     * 格式: {"output":{"choices":[{"message":{"content":"...增量文本...","tool_calls":[...]}, "finish_reason":"..."}]}, "usage":{...}, "request_id":"..."}
     */
    protected ChatCompletionsResponse parseStreamChunk(String line) {
        try {
            JsonNode root = http.json().readTree(line);
            String id = textOrNull(root, "request_id");
            JsonNode output = root.path("output");
            JsonNode usage = root.path("usage");
            TokenUsage tu = TokenUsage.empty();
            if (usage.isObject() && usage.size() > 0) {
                tu = new TokenUsage(
                        usage.path("input_tokens").asLong(0L),
                        usage.path("output_tokens").asLong(0L),
                        usage.path("total_tokens").asLong(0L));
            }
            List<ChatCompletionsResponse.Choice> choices = new ArrayList<>();
            String finish = null;
            JsonNode arr = output.path("choices");
            for (int i = 0; i < arr.size(); i++) {
                JsonNode c = arr.get(i);
                JsonNode msg = c.path("message");
                String content = textOrNull(msg, "content");
                if (content == null) content = "";
                String fr = textOrNull(c, "finish_reason");
                if (fr != null) finish = fr;
                List<ToolCall> tcs = new ArrayList<>();
                JsonNode tcArr = msg.path("tool_calls");
                if (tcArr.isArray()) {
                    for (JsonNode t : tcArr) {
                        JsonNode fn = t.path("function");
                        tcs.add(new ToolCall(textOrNull(t, "id"), textOrNull(fn, "name"), textOrNull(fn, "arguments")));
                    }
                }
                choices.add(new ChatCompletionsResponse.Choice(i, content, tcs, fr));
            }
            return new ChatCompletionsResponse(id, null, choices, tu, finish, Collections.emptyMap());
        } catch (Exception e) {
            throw new LlmException(name(), "parseStreamChunk failed: " + line, e);
        }
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

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", toDashScopeMessages(request.getMessages()));
        body.put("input", input);

        Map<String, Object> parameters = new LinkedHashMap<>();
        if (request.getTemperature() != null) parameters.put("temperature", request.getTemperature());
        if (request.getTopP() != null) parameters.put("top_p", request.getTopP());
        if (request.getMaxTokens() != null) parameters.put("max_tokens", request.getMaxTokens());
        if (stream) parameters.put("incremental_output", true);
        parameters.put("result_format", "message");
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            // DashScope 工具调用走 result_format=message, 但 tools 字段在 parameters 里
            List<Map<String, Object>> tools = new ArrayList<>();
            for (com.zifang.z.agent.kernel.tool.Tool t : request.getTools()) {
                Map<String, Object> at = new LinkedHashMap<>();
                at.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", t.getName());
                fn.put("description", t.getDescription() == null ? "" : t.getDescription());
                fn.put("parameters", t.getSchema() == null ? Collections.emptyMap() : t.getSchema());
                at.put("function", fn);
                tools.add(at);
            }
            parameters.put("tools", tools);
        }
        if (request.getProviderParams() != null && !request.getProviderParams().isEmpty()) {
            parameters.putAll(request.getProviderParams());
        }
        body.put("parameters", parameters);
        return body;
    }

    protected List<Map<String, Object>> toDashScopeMessages(List<Msg> msgs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Msg m : msgs) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("role", toDashScopeRole(m.getRole()));
            if (m.getRole() == MessageRole.SYSTEM || m.getRole() == MessageRole.USER || m.getRole() == MessageRole.FUNCTION) {
                dm.put("content", m.getContent() == null ? "" : m.getContent());
            } else if (m.getRole() == MessageRole.ASSISTANT) {
                if (!m.getToolCalls().isEmpty()) {
                    List<Map<String, Object>> tcs = new ArrayList<>();
                    for (ToolCall tc : m.getToolCalls()) {
                        Map<String, Object> tcm = new LinkedHashMap<>();
                        tcm.put("id", tc.getId());
                        Map<String, Object> fn = new LinkedHashMap<>();
                        fn.put("name", tc.getName());
                        fn.put("arguments", tc.getArgumentsJson() == null ? "{}" : tc.getArgumentsJson());
                        tcm.put("function", fn);
                        tcs.add(tcm);
                    }
                    dm.put("tool_calls", tcs);
                    if (m.getContent() != null && !m.getContent().isEmpty()) {
                        dm.put("content", m.getContent());
                    }
                } else {
                    dm.put("content", m.getContent() == null ? "" : m.getContent());
                }
            } else if (m.getRole() == MessageRole.TOOL) {
                dm.put("content", m.getContent() == null ? "" : m.getContent());
                dm.put("tool_call_id", m.getToolCallId() == null ? "" : m.getToolCallId());
            }
            out.add(dm);
        }
        return out;
    }

    protected String toDashScopeRole(MessageRole role) {
        switch (role) {
            case SYSTEM: return "system";
            case USER: return "user";
            case ASSISTANT: return "assistant";
            case TOOL: return "tool";
            case FUNCTION: return "function";
            default: return "user";
        }
    }

    protected ChatCompletionsResponse parseResponse(String raw) {
        try {
            JsonNode root = http.json().readTree(raw);
            String id = textOrNull(root.path("request_id"), null);
            if (id == null) id = textOrNull(root, "request_id");
            JsonNode output = root.path("output");
            JsonNode usage = root.path("usage");
            TokenUsage tu = new TokenUsage(
                    usage.path("input_tokens").asLong(0L),
                    usage.path("output_tokens").asLong(0L),
                    usage.path("total_tokens").asLong(0L));

            List<ChatCompletionsResponse.Choice> choices = new ArrayList<>();
            JsonNode arr = output.path("choices");
            if (arr.isArray()) {
                for (int i = 0; i < arr.size(); i++) {
                    JsonNode c = arr.get(i);
                    JsonNode msg = c.path("message");
                    String content = textOrNull(msg, "content");
                    String finish = textOrNull(c, "finish_reason");
                    List<ToolCall> tcs = new ArrayList<>();
                    JsonNode tcArr = msg.path("tool_calls");
                    if (tcArr.isArray()) {
                        for (JsonNode t : tcArr) {
                            JsonNode fn = t.path("function");
                            tcs.add(new ToolCall(textOrNull(t, "id"), textOrNull(fn, "name"), textOrNull(fn, "arguments")));
                        }
                    }
                    choices.add(new ChatCompletionsResponse.Choice(i, content, tcs, finish));
                }
            } else {
                String text = textOrNull(output, "text");
                choices.add(new ChatCompletionsResponse.Choice(0, text == null ? "" : text, Collections.emptyList(), "stop"));
            }
            return new ChatCompletionsResponse(id, null, choices, tu, null, Collections.emptyMap());
        } catch (Exception e) {
            throw new LlmException(name(), "parseResponse failed: " + raw, e);
        }
    }

    protected static String textOrNull(JsonNode node, String field) {
        if (field == null) {
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        }
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}