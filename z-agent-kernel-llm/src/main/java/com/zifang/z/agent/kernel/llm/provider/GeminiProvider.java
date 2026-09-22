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
 * Google Gemini generateContent API provider.
 *
 * <p>默认 base URL: https://generativelanguage.googleapis.com
 * <p>协议独立: contents[].parts[] + generationConfig, 无 messages 数组, role 只有 user/model.
 */
public class GeminiProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final String apiKey;
    private final String apiBase;
    private final LlmHttp http;

    public GeminiProvider(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public GeminiProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase == null || apiBase.isEmpty() ? DEFAULT_BASE_URL : apiBase;
        this.http = new LlmHttp();
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public List<Model> listModels() {
        List<Model> out = new ArrayList<>();
        out.add(new Model("gemini-2.5-pro", "Gemini 2.5 Pro", "gemini",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION, Model.Capability.JSON_MODE),
                1000000L, 65536L));
        out.add(new Model("gemini-2.5-flash", "Gemini 2.5 Flash", "gemini",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION, Model.Capability.JSON_MODE),
                1000000L, 65536L));
        out.add(new Model("gemini-2.0-flash", "Gemini 2.0 Flash", "gemini",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION),
                1000000L, 8192L));
        out.add(new Model("gemini-1.5-pro", "Gemini 1.5 Pro", "gemini",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION),
                2000000L, 8192L));
        out.add(new Model("gemini-1.5-flash", "Gemini 1.5 Flash", "gemini",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.VISION),
                1000000L, 8192L));
        out.add(new Model("gemini-1.5-flash-8b", "Gemini 1.5 Flash-8B", "gemini",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM),
                1000000L, 8192L));
        return out;
    }

    @Override
    public boolean supportsModel(String modelId) {
        if (modelId == null) return false;
        return modelId.toLowerCase().startsWith("gemini-");
    }

    @Override
    public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
        Map<String, Object> body = buildRequestBody(request, false);
        String url = apiBase + "/v1beta/models/" + request.getModel() + ":generateContent";
        String raw = http.postJson(url, authHeaders(), body);
        return parseResponse(raw);
    }

    @Override
    public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk, Consumer<Throwable> onError) {
        Map<String, Object> body = buildRequestBody(request, true);
        // ?alt=sse 让 Gemini 返回标准 SSE 格式 (默认是 JSONL)
        String url = apiBase + "/v1beta/models/" + request.getModel() + ":streamGenerateContent?alt=sse";
        http.postJsonStream(url, authHeaders(), body,
                line -> {
                    ChatCompletionsResponse chunk = parseStreamChunk(line);
                    if (chunk != null) onChunk.accept(chunk);
                },
                onError,
                null);
    }

    /**
     * 解析 Gemini SSE 流式 chunk.
     * 格式: {"candidates":[{"content":{"parts":[{"text":"...增量..."}],"role":"model"}, "finishReason":"..."}], "usageMetadata":{...}, "modelVersion":"..."}
     */
    protected ChatCompletionsResponse parseStreamChunk(String line) {
        try {
            JsonNode root = http.json().readTree(line);
            String id = textOrNull(root, "modelVersion");
            JsonNode u = root.path("usageMetadata");
            TokenUsage usage = TokenUsage.empty();
            if (u.isObject() && u.size() > 0) {
                usage = new TokenUsage(
                        u.path("promptTokenCount").asLong(0L),
                        u.path("candidatesTokenCount").asLong(0L),
                        u.path("totalTokenCount").asLong(0L));
            }
            List<ChatCompletionsResponse.Choice> choices = new ArrayList<>();
            String finish = null;
            JsonNode arr = root.path("candidates");
            for (int i = 0; i < arr.size(); i++) {
                JsonNode c = arr.get(i);
                StringBuilder textBuf = new StringBuilder();
                List<ToolCall> tcs = new ArrayList<>();
                JsonNode parts = c.path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode p : parts) {
                        JsonNode tp = p.path("text");
                        if (!tp.isMissingNode() && !tp.isNull()) {
                            if (textBuf.length() > 0) textBuf.append("\n");
                            textBuf.append(tp.asText());
                        }
                        JsonNode fc = p.path("functionCall");
                        if (!fc.isMissingNode() && !fc.isNull()) {
                            String argsJson = http.json().writeValueAsString(fc.path("args"));
                            tcs.add(new ToolCall(null, textOrNull(fc, "name"), argsJson));
                        }
                    }
                }
                String fr = textOrNull(c, "finishReason");
                if (fr != null) finish = fr;
                choices.add(new ChatCompletionsResponse.Choice(i, textBuf.toString(), tcs, fr));
            }
            return new ChatCompletionsResponse(id, null, choices, usage, finish, Collections.emptyMap());
        } catch (Exception e) {
            throw new LlmException(name(), "parseStreamChunk failed: " + line, e);
        }
    }

    // ---- 内部 helper ----

    protected Headers authHeaders() {
        return new Headers.Builder()
                .add("x-goog-api-key", apiKey)
                .add("Content-Type", "application/json")
                .build();
    }

    protected Map<String, Object> buildRequestBody(ChatCompletionsRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();

        // systemInstruction
        StringBuilder sysBuf = new StringBuilder();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Msg m : request.getMessages()) {
            if (m.getRole() == MessageRole.SYSTEM) {
                if (sysBuf.length() > 0) sysBuf.append("\n\n");
                sysBuf.append(m.getContent());
                continue;
            }
            contents.add(toGeminiContent(m));
        }
        if (sysBuf.length() > 0) {
            Map<String, Object> sysInst = new LinkedHashMap<>();
            List<Map<String, Object>> parts = new ArrayList<>();
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("text", sysBuf.toString());
            parts.add(textPart);
            sysInst.put("parts", parts);
            body.put("systemInstruction", sysInst);
        }
        body.put("contents", contents);

        // generationConfig
        Map<String, Object> genConfig = new LinkedHashMap<>();
        if (request.getTemperature() != null) genConfig.put("temperature", request.getTemperature());
        if (request.getTopP() != null) genConfig.put("topP", request.getTopP());
        if (request.getMaxTokens() != null) genConfig.put("maxOutputTokens", request.getMaxTokens());
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> fnDecls = new ArrayList<>();
            for (com.zifang.z.agent.kernel.tool.Tool t : request.getTools()) {
                Map<String, Object> decl = new LinkedHashMap<>();
                decl.put("name", t.getName());
                decl.put("description", t.getDescription() == null ? "" : t.getDescription());
                decl.put("parameters", t.getSchema() == null ? Collections.emptyMap() : t.getSchema());
                fnDecls.add(decl);
            }
            Map<String, Object> toolsWrap = new LinkedHashMap<>();
            toolsWrap.put("functionDeclarations", fnDecls);
            List<Map<String, Object>> toolsList = new ArrayList<>();
            toolsList.add(toolsWrap);
            genConfig.put("tools", toolsList);
        }
        if (request.getProviderParams() != null && !request.getProviderParams().isEmpty()) {
            genConfig.putAll(request.getProviderParams());
        }
        if (!genConfig.isEmpty()) body.put("generationConfig", genConfig);
        return body;
    }

    protected Map<String, Object> toGeminiContent(Msg m) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (m.getRole()) {
            case ASSISTANT: out.put("role", "model"); break;
            case USER:
            case FUNCTION:
            case TOOL:
            default: out.put("role", "user");
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        if (m.getContent() != null && !m.getContent().isEmpty()) {
            Map<String, Object> tp = new LinkedHashMap<>();
            tp.put("text", m.getContent());
            parts.add(tp);
        }
        if (m.getRole() == MessageRole.TOOL && m.getToolCallId() != null) {
            Map<String, Object> fp = new LinkedHashMap<>();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("name", m.getName() == null ? "" : m.getName());
            resp.put("content", m.getContent() == null ? "" : m.getContent());
            fp.put("functionResponse", resp);
            parts.add(fp);
        }
        for (ToolCall tc : m.getToolCalls()) {
            Map<String, Object> fp = new LinkedHashMap<>();
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("name", tc.getName());
            call.put("args", parseArgsOrEmpty(tc.getArgumentsJson()));
            fp.put("functionCall", call);
            parts.add(fp);
        }
        out.put("parts", parts);
        return out;
    }

    protected ChatCompletionsResponse parseResponse(String raw) {
        try {
            JsonNode root = http.json().readTree(raw);
            String id = textOrNull(root, "modelVersion");
            JsonNode u = root.path("usageMetadata");
            TokenUsage usage = new TokenUsage(
                    u.path("promptTokenCount").asLong(0L),
                    u.path("candidatesTokenCount").asLong(0L),
                    u.path("totalTokenCount").asLong(0L));

            List<ChatCompletionsResponse.Choice> choices = new ArrayList<>();
            JsonNode arr = root.path("candidates");
            for (int i = 0; i < arr.size(); i++) {
                JsonNode c = arr.get(i);
                StringBuilder textBuf = new StringBuilder();
                List<ToolCall> tcs = new ArrayList<>();
                JsonNode parts = c.path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode p : parts) {
                        JsonNode tp = p.path("text");
                        if (!tp.isMissingNode()) {
                            if (textBuf.length() > 0) textBuf.append("\n");
                            textBuf.append(tp.asText());
                        }
                        JsonNode fc = p.path("functionCall");
                        if (!fc.isMissingNode() && !fc.isNull()) {
                            String argsJson = http.json().writeValueAsString(fc.path("args"));
                            tcs.add(new ToolCall(null, textOrNull(fc, "name"), argsJson));
                        }
                    }
                }
                String finish = textOrNull(c, "finishReason");
                choices.add(new ChatCompletionsResponse.Choice(i, textBuf.toString(), tcs, finish));
            }
            return new ChatCompletionsResponse(id, null, choices, usage, null, Collections.emptyMap());
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