package com.zifang.z.agent.kernel.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 多 provider 故障切换链 —— kernel 侧的**基建能力**: 把「本地免费模型 / 云厂商 / 备用厂商」
 * 排成一条链, 前一个不可用(连不上 / 鉴权失败 / 限流 / 超时)就自动切下一个.
 *
 * <p>存在的理由: 上层业务不该为「今天云厂商额度用完了」改代码.
 * 典型用法:
 * <pre>
 * FailoverLlmProvider llm = new FailoverLlmProvider(
 *     FailoverLlmProvider.entry("ollama",  new OllamaProvider(), "qwen2.5:7b-instruct-q4_K_M"),
 *     FailoverLlmProvider.entry("minimax", new OpenAIProvider(key, "https://api.minimaxi.com/v1"), "MiniMax-M3.1-Flash"));
 * </pre>
 *
 * <p>切换规则(重要):
 * <ul>
 *   <li><b>还没吐出任何内容</b>就失败 ⇒ 切下一个(连接/鉴权/限流/超时这类"还没说话就挂了"的故障);</li>
 *   <li><b>已经吐出内容</b>后失败 ⇒ **不切换**, 直接把错误抛给上层
 *       (切过去会把上一家已说的半句和新一家的开头拼在一起, 语义会错乱);
 *       <b>空 chunk 不算"内容"</b>(只有 role 没有 content 的那种) —— 见 {@code carriesContent}.</li>
 * </ul>
 *
 * <p>熔断: 同一 provider <b>连续失败 {@code failureThreshold} 次</b> ⇒ 熔断 {@code skipMillis} 毫秒,
 * 期间直接跳过(避免每轮都先撞它); 成功一次即清零.
 *
 * <p>Java 8 兼容(本仓 compiler.source/target=8).
 */
public final class FailoverLlmProvider implements LlmProvider {

    /** 默认连续失败多少次后熔断. */
    public static final int DEFAULT_FAILURE_THRESHOLD = 2;
    /** 默认熔断时长(毫秒). */
    public static final long DEFAULT_SKIP_MILLIS = 30_000L;

    /** 链上的一个 provider(名字 + 实现 + 该家用的模型 id). */
    public static final class Entry {
        private final String name;
        private final LlmProvider provider;
        private final String model;
        private int consecutiveFailures;
        private long skipUntil;

        Entry(String name, LlmProvider provider, String model) {
            this.name = name;
            this.provider = provider;
            this.model = model;
        }

        public String name() {
            return name;
        }

        public LlmProvider provider() {
            return provider;
        }

        /** 该 provider 使用的模型 id(null ⇒ 用请求里的 model). */
        public String model() {
            return model;
        }
    }

    private final List<Entry> entries;
    private final int failureThreshold;
    private final long skipMillis;

    public FailoverLlmProvider(List<Entry> entries) {
        this(entries, DEFAULT_FAILURE_THRESHOLD, DEFAULT_SKIP_MILLIS);
    }

    public FailoverLlmProvider(List<Entry> entries, int failureThreshold, long skipMillis) {
        this.entries = entries == null ? Collections.<Entry>emptyList()
                : Collections.unmodifiableList(new ArrayList<Entry>(entries));
        this.failureThreshold = failureThreshold <= 0 ? DEFAULT_FAILURE_THRESHOLD : failureThreshold;
        this.skipMillis = skipMillis <= 0 ? DEFAULT_SKIP_MILLIS : skipMillis;
    }

    /** 便捷构造: 不覆盖模型 id(全都用请求里的 model). */
    public FailoverLlmProvider(LlmProvider... providers) {
        this(entriesOf(providers));
    }

    private static List<Entry> entriesOf(LlmProvider... providers) {
        List<Entry> list = new ArrayList<Entry>();
        if (providers != null) {
            for (LlmProvider p : providers) {
                if (p != null) {
                    list.add(entry(p.name(), p, null));
                }
            }
        }
        return list;
    }

    public static Entry entry(String name, LlmProvider provider, String model) {
        return new Entry(name, provider, model);
    }

    public static Entry entry(LlmProvider provider) {
        return new Entry(provider.name(), provider, null);
    }

    /** 链上各 provider 的名字(健康检查/日志用). */
    public List<String> names() {
        List<String> out = new ArrayList<String>();
        for (Entry e : entries) {
            out.add(e.name);
        }
        return out;
    }

    /** 链上第 i 个 provider(诊断用). */
    public Entry entryAt(int index) {
        return entries.get(index);
    }

    @Override
    public String name() {
        return "failover" + names();
    }

    @Override
    public List<Model> listModels() {
        List<Model> out = new ArrayList<Model>();
        for (Entry e : entries) {
            List<Model> models = e.provider.listModels();
            if (models != null) {
                out.addAll(models);
            }
        }
        return out;
    }

    @Override
    public boolean supportsModel(String modelId) {
        for (Entry e : entries) {
            if (e.provider.supportsModel(modelId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<String, Object> providerParams() {
        return entries.isEmpty() ? Collections.<String, Object>emptyMap()
                : entries.get(0).provider.providerParams();
    }

    @Override
    public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
        RuntimeException last = null;
        for (Entry e : entries) {
            if (skipped(e)) {
                continue;
            }
            try {
                ChatCompletionsResponse resp = e.provider.chat(withModel(request, e));
                e.consecutiveFailures = 0;
                return resp;
            } catch (RuntimeException ex) {
                e.consecutiveFailures++;
                markSkipped(e);
                last = ex;
            }
        }
        throw last != null ? last : new IllegalStateException("所有 LLM provider 均不可用: " + names());
    }

    @Override
    public void streamChat(ChatCompletionsRequest request,
                            Consumer<ChatCompletionsResponse> onChunk,
                            Consumer<Throwable> onError) {
        streamChat(request, onChunk, onError, null);
    }

    @Override
    public void streamChat(ChatCompletionsRequest request,
                            Consumer<ChatCompletionsResponse> onChunk,
                            Consumer<Throwable> onError,
                            Runnable onComplete) {
        streamInternal(request, onChunk, onError, onComplete, 0);
    }

    private void streamInternal(final ChatCompletionsRequest request,
                                 final Consumer<ChatCompletionsResponse> onChunk,
                                 final Consumer<Throwable> onError,
                                 final Runnable onComplete,
                                 final int startIndex) {
        int cursor = startIndex;
        while (cursor < entries.size() && skipped(entries.get(cursor))) {
            cursor++;
        }
        if (cursor >= entries.size()) {
            onError.accept(new IllegalStateException("所有 LLM provider 均不可用: " + names()));
            return;
        }
        final int index = cursor;
        final Entry entry = entries.get(index);
        // 是否已经吐出**内容**(空 chunk 不算 —— 见 carriesContent 的说明)
        final boolean[] produced = {false};
        try {
            entry.provider.streamChat(withModel(request, entry), chunk -> {
                if (carriesContent(chunk)) {
                    produced[0] = true;
                }
                onChunk.accept(chunk);
            }, err -> {
                entry.consecutiveFailures++;
                markSkipped(entry);
                if (!produced[0] && index + 1 < entries.size()) {
                    streamInternal(request, onChunk, onError, onComplete, index + 1);
                } else {
                    onError.accept(err);
                }
            }, onComplete);
        } catch (RuntimeException e) {
            entry.consecutiveFailures++;
            markSkipped(entry);
            if (!produced[0] && index + 1 < entries.size()) {
                streamInternal(request, onChunk, onError, onComplete, index + 1);
            } else {
                onError.accept(e);
            }
        }
    }

    /** 切换到下一个 provider 时, 用它自己的模型 id 覆盖请求里的 model. */
    private static ChatCompletionsRequest withModel(ChatCompletionsRequest request, Entry entry) {
        if (entry.model == null || entry.model.isEmpty() || request == null) {
            return request;
        }
        if (entry.model.equals(request.getModel())) {
            return request;
        }
        return new ChatCompletionsRequest(
                entry.model, request.getMessages(), request.getTools(),
                request.getTemperature(), request.getTopP(), request.getMaxTokens(),
                request.isStream(), request.getProviderParams());
    }

    /**
     * 这个 chunk 有没有**真正带出内容**.
     *
     * <p>为什么要区分"chunk 来了"和"内容来了": OpenAI 兼容协议的第一个 chunk
     * 通常只有 {@code role} 没有 {@code content}; Ollama 连不上时也常先吐一个空 chunk 再报错.
     * 若把空 chunk 算成"已产出", 那就**永远切不了** —— 而此时切过去恰恰是最该切的
     * (用户一个字的没听到, 换成下一家毫无副作用).
     *
     * <p>tool call 也算内容: 那一轮已经决定了要调什么工具, 换家会让工具调用重复.
     */
    private static boolean carriesContent(ChatCompletionsResponse chunk) {
        if (chunk == null || chunk.getChoices() == null) {
            return false;
        }
        for (ChatCompletionsResponse.Choice c : chunk.getChoices()) {
            if (c.getContent() != null && !c.getContent().isEmpty()) {
                return true;
            }
            if (c.getToolCalls() != null && !c.getToolCalls().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean skipped(Entry e) {
        return e.skipUntil > System.currentTimeMillis();
    }

    private void markSkipped(Entry e) {
        if (e.consecutiveFailures >= failureThreshold) {
            e.skipUntil = System.currentTimeMillis() + skipMillis;
        }
    }

    /** 供诊断: 当前被熔断跳过的 provider 名字. */
    public List<String> skippedNow() {
        List<String> out = new ArrayList<String>();
        long now = System.currentTimeMillis();
        for (Entry e : entries) {
            if (e.skipUntil > now) {
                out.add(e.name);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "FailoverLlmProvider" + Arrays.toString(names().toArray());
    }
}
