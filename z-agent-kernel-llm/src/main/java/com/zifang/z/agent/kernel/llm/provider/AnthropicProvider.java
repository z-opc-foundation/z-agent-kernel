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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Anthropic Messages API provider.
 *
 * <p>默认 base URL: https://api.anthropic.com
 * <p>协议独立, 不走 OpenAI 兼容层: messages 在 body 里, system 在顶层, content 是 block 数组.
 */
public class AnthropicProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String apiBase;
    private final LlmHttp http;

    public AnthropicProvider(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public AnthropicProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase == null || apiBase.isEmpty() ? DEFAULT_BASE_URL : apiBase;
        this.http = new LlmHttp();
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public List<Model> listModels() {
        List<Model> out = new ArrayList<>();
        out.add(new Model("claude-opus-4-20250514", "Claude Opus 4", "anthropic",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION),
                200000L, 32000L));
        out.add(new Model("claude-sonnet-4-20250514", "Claude Sonnet 4", "anthropic",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION),
                200000L, 64000L));
        out.add(new Model("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet", "anthropic",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION),
                200000L, 64000L));
        out.add(new Model("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet v2", "anthropic",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION),
                200000L, 8192L));
        out.add(new Model("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", "anthropic",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS),
                200000L, 8192L));
        return out;
    }

    @Override
    public boolean supportsModel(String modelId) {
        if (modelId == null) return false;
        return modelId.toLowerCase().startsWith("claude-");
    }

    @Override
    public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
        Map<String, Object> body = buildRequestBody(request, false);
        String raw = http.postJson(apiBase + "/v1/messages", authHeaders(), body);
        return parseResponse(raw);
    }

    @Override
    public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk, Consumer<Throwable> onError) {
        // Anthropic SSE 同时用 event: + data: 两行, okhttp-sse EventSource 只暴露 data 字段.
        // 这里走原始 BufferedReader 逐行解析 event+data.
        Map<String, Object> body = buildRequestBody(request, true);
        try {
            String bodyJson = http.json().writeValueAsString(body);
            okhttp3.Request httpReq = new okhttp3.Request.Builder()
                    .url(apiBase + "/v1/messages")
                    .headers(authHeaders())
                    .post(okhttp3.RequestBody.create(bodyJson, LlmHttp.JSON))
                    .build();
            http.client().newCall(httpReq).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, IOException e) {
                    onError.accept(new LlmException(name(), "stream I/O", e));
                }

                @Override
                public void onResponse(okhttp3.Call call, okhttp3.Response resp) {
                    try (okhttp3.ResponseBody rb = resp.body()) {
                        if (!resp.isSuccessful()) {
                            String text = rb == null ? "" : rb.string();
                            onError.accept(new LlmException(name(), resp.code(), text));
                            return;
                        }
                        if (rb == null) {
                            onError.accept(new LlmException(name(), "empty response body"));
                            return;
                        }
                        java.io.BufferedReader br = new java.io.BufferedReader(rb.charStream());
                        String line;
                        String event = null;
                        StringBuilder dataBuf = new StringBuilder();
                        while ((line = br.readLine()) != null) {
                            if (line.isEmpty()) {
                                if (event != null && dataBuf.length() > 0) {
                                    try {
                                        ChatCompletionsResponse chunk = parseAnthropicEvent(event, dataBuf.toString());
                                        if (chunk != null) onChunk.accept(chunk);
                                    } catch (Throwable t) {
                                        onError.accept(new LlmException(name(), "parseEvent", t));
                                    }
                                }
                                event = null;
                                dataBuf = new StringBuilder();
                                continue;
                            }
                            if (line.startsWith("event: ")) {
                                event = line.substring(7).trim();
                            } else if (line.startsWith("data: ")) {
                                if (dataBuf.length() > 0) dataBuf.append("\n");
                                dataBuf.append(line.substring(6));
                            }
                        }
                    } catch (Exception e) {
                        onError.accept(new LlmException(name(), "stream read", e));
                    }
                }
            });
        } catch (Exception e) {
            onError.accept(new LlmException(name(), "stream setup", e));
        }
    }

    /**
     * 解析单条 Anthropic SSE 事件 (event 头 + data JSON).
     * 事件类型: message_start / content_block_start / content_block_delta / content_block_stop /
     *          message_delta / message_stop / ping / error.
     */
    protected ChatCompletionsResponse parseAnthropicEvent(String event, String data) {
        try {
            JsonNode root = http.json().readTree(data);
            switch (event) {
                case "message_start": {
                    String id = textOrNull(root.path("message"), "id");
                    String model = textOrNull(root.path("message"), "model");
                    JsonNode u = root.path("message").path("usage");
                    TokenUsage usage = new TokenUsage(u.path("input_tokens").asLong(0L),
                            u.path("output_tokens").asLong(0L),
                            u.path("input_tokens").asLong(0L) + u.path("output_tokens").asLong(0L));
                    List<ChatCompletionsResponse.Choice> choices = new ArrayList<>();
                    choices.add(new ChatCompletionsResponse.Choice(0, "", Collections.emptyList(), null));
                    return new ChatCompletionsResponse(id, model, choices, usage, null, Collections.emptyMap());
                }
                case "content_block_delta": {
                    String id = lastMessageId;
                    String model = lastMessageModel;
                    JsonNode delta = root.path("delta");
                    StringBuilder textBuf = new StringBuilder();
                    if ("text_delta".equals(textOrNull(delta, "type"))) {
                        textBuf.append(textOrNull(delta, "text"));
                    } else if ("input_json_delta".equals(textOrNull(delta, "type"))) {
                        // tool 输入 JSON 增量, kernel 不暴露原始增量, 跳过
                    }
                    List<ChatCompletionsResponse.Choice> choices = new ArrayList<>();
                    choices.add(new ChatCompletionsResponse.Choice(0, textBuf.toString(), Collections.emptyList(), null));
                    return new ChatCompletionsResponse(id, model, choices, TokenUsage.empty(), null, Collections.emptyMap());
                }
                case "message_delta": {
                    String stopReason = textOrNull(root.path("delta"), "stop_reason");
                    JsonNode u = root.path("usage");
                    TokenUsage usage = new TokenUsage(u.path("input_tokens").asLong(0L),
                            u.path("output_tokens").asLong(0L),
                            u.path("input_tokens").asLong(0L) + u.path("output_tokens").asLong(0L));
                    List<ChatCompletionsResponse.Choice> choices = new ArrayList<>();
                    choices.add(new ChatCompletionsResponse.Choice(0, "", Collections.emptyList(), stopReason));
                    return new ChatCompletionsResponse(lastMessageId, lastMessageModel, choices, usage, stopReason, Collections.emptyMap());
                }
                case "message_stop":
                case "ping":
                case "content_block_start":
                case "content_block_stop":
                case "error":
                default:
                    // 不关心的事件, 不产生 chunk
                    return null;
            }
        } catch (Exception e) {
            throw new LlmException(name(), "parseAnthropicEvent(" + event + ") failed: " + data, e);
        }
    }

    private String lastMessageId = null;
    private String lastMessageModel = null;

    // ---- 内部 helper ----

    protected Headers authHeaders() {
        return new Headers.Builder()
                .add("x-api-key", apiKey)
                .add("anthropic-version", ANTHROPIC_VERSION)
                .add("Content-Type", "application/json")
                .build();
    }

    protected Map<String, Object> buildRequestBody(ChatCompletionsRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());

        // Anthropic 把 system 提到顶层, 不放 messages
        StringBuilder sysBuf = new StringBuilder();
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Msg m : request.getMessages()) {
            if (m.getRole() == MessageRole.SYSTEM) {
                if (sysBuf.length() > 0) sysBuf.append("\n\n");
                sysBuf.append(m.getContent());
                continue;
            }
            msgs.add(toAnthropicMessage(m));
        }
        if (sysBuf.length() > 0) body.put("system", sysBuf.toString());
        body.put("messages", msgs);

        // max_tokens 必填, 默认 8192
        body.put("max_tokens", request.getMaxTokens() == null ? 8192 : request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP() != null) body.put("top_p", request.getTopP());
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (com.zifang.z.agent.kernel.tool.Tool t : request.getTools()) {
                Map<String, Object> at = new LinkedHashMap<>();
                at.put("name", t.getName());
                at.put("description", t.getDescription() == null ? "" : t.getDescription());
                at.put("input_schema", t.getSchema() == null ? Collections.emptyMap() : t.getSchema());
                tools.add(at);
            }
            body.put("tools", tools);
        }
        if (stream) body.put("stream", true);
        if (request.getProviderParams() != null && !request.getProviderParams().isEmpty()) {
            body.putAll(request.getProviderParams());
        }
        return body;
    }

    protected Map<String, Object> toAnthropicMessage(Msg m) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (m.getRole()) {
            case ASSISTANT:
                out.put("role", "assistant");
                if (!m.getToolCalls().isEmpty()) {
                    List<Map<String, Object>> blocks = new ArrayList<>();
                    if (m.getContent() != null && !m.getContent().isEmpty()) {
                        Map<String, Object> text = new LinkedHashMap<>();
                        text.put("type", "text");
                        text.put("text", m.getContent());
                        blocks.add(text);
                    }
                    for (ToolCall tc : m.getToolCalls()) {
                        Map<String, Object> tu = new LinkedHashMap<>();
                        tu.put("type", "tool_use");
                        tu.put("id", tc.getId());
                        tu.put("name", tc.getName());
                        tu.put("input", parseArgsOrEmpty(tc.getArgumentsJson()));
                        blocks.add(tu);
                    }
                    out.put("content", blocks);
                } else {
                    out.put("content", m.getContent() == null ? "" : m.getContent());
                }
                break;
            case USER:
            case FUNCTION:
                out.put("role", "user");
                out.put("content", m.getContent() == null ? "" : m.getContent());
                break;
            case TOOL:
                // tool_result 必须挂在 user 消息里
                out.put("role", "user");
                List<Map<String, Object>> blocks = new ArrayList<>();
                Map<String, Object> tr = new LinkedHashMap<>();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", m.getToolCallId() == null ? "" : m.getToolCallId());
                tr.put("content", m.getContent() == null ? "" : m.getContent());
                blocks.add(tr);
                out.put("content", blocks);
                break;
            default:
                out.put("role", "user");
                out.put("content", m.getContent() == null ? "" : m.getContent());
        }
        return out;
    }

    protected ChatCompletionsResponse parseResponse(String raw) {
        try {
            JsonNode root = http.json().readTree(raw);
            String id = textOrNull(root, "id");
            String model = textOrNull(root, "model");
            String stopReason = textOrNull(root, "stop_reason");

            // content blocks → 拼出 content 字符串 + 提取 tool_use
            StringBuilder textBuf = new StringBuilder();
            List<ToolCall> tcs = new ArrayList<>();
            JsonNode blocks = root.path("content");
            if (blocks.isArray()) {
                for (JsonNode b : blocks) {
                    String type = textOrNull(b, "type");
                    if ("text".equals(type)) {
                        if (textBuf.length() > 0) textBuf.append("\n");
                        textBuf.append(textOrNull(b, "text"));
                    } else if ("tool_use".equals(type)) {
                        String argsJson = http.json().writeValueAsString(b.path("input"));
                        tcs.add(new ToolCall(textOrNull(b, "id"), textOrNull(b, "name"), argsJson));
                    }
                }
            }

            JsonNode u = root.path("usage");
            TokenUsage usage = new TokenUsage(
                    u.path("input_tokens").asLong(0L),
                    u.path("output_tokens").asLong(0L),
                    u.path("input_tokens").asLong(0L) + u.path("output_tokens").asLong(0L));

            List<ChatCompletionsResponse.Choice> choices = new ArrayList<>();
            choices.add(new ChatCompletionsResponse.Choice(0, textBuf.toString(), tcs, stopReason));
            return new ChatCompletionsResponse(id, model, choices, usage, stopReason, Collections.emptyMap());
        } catch (Exception e) {
            throw new LlmException(name(), "parseResponse failed: " + raw, e);
        }
    }

    protected Object parseArgsOrEmpty(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try {
            return http.json().readTree(json);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    protected static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}