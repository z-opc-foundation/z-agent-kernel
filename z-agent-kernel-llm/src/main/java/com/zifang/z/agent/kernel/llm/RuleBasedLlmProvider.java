package com.zifang.z.agent.kernel.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;

/**
 * <b>零模型</b>的规则应答器 —— 不调用任何大模型, 只按关键词匹配给出预设回复.
 *
 * <p>存在的理由: 语音链路的**骨架**（ASR 听 → 策略 → TTS 说）不应该被"LLM 额度用完了"卡死.
 * 语音接口/电话/客服的第一档应答本来就常常是**规则/FAQ**, 不需要大模型;
 * 大模型是"第二档"（复杂问题)才上的.
 *
 * <p>用法:
 * <pre>
 * LlmProvider rule = new RuleBasedLlmProvider(
 *     RuleBasedLlmProvider.rule("你好,你能做什么", "你好,我是语音助手,你可以直接问我问题."),
 *     RuleBasedLlmProvider.rule("天气", "我帮你查一下天气,请告诉我城市名."));
 * </pre>
 *
 * <p>匹配规则: 取最后一条 user 消息, 命中任一关键词即返回该规则的话术;
 * 都没命中 ⇒ 返回 fallback(默认"我没听懂,换个说法试试").
 * 支持多个关键词(任一命中即可).
 *
 * <p>流式: {@link #streamChat} 按**句读切分**逐块吐出(模拟真流式的节奏),
 * 让上层的"边生成边播报"链路在**没有模型**时也能被完整验证.
 */
public final class RuleBasedLlmProvider implements LlmProvider {

    /** 一条规则: 命中任一关键词 ⇒ 回复该话术. */
    public static final class Rule {
        private final List<String> keywords;
        private final String reply;

        Rule(List<String> keywords, String reply) {
            this.keywords = Collections.unmodifiableList(new ArrayList<String>(keywords));
            this.reply = reply;
        }

        public List<String> keywords() {
            return keywords;
        }

        public String reply() {
            return reply;
        }
    }

    private final List<Rule> rules;
    private final String fallback;

    public RuleBasedLlmProvider(List<Rule> rules) {
        this(rules, "我没听清,换个说法试试吧.");
    }

    public RuleBasedLlmProvider(List<Rule> rules, String fallback) {
        this.rules = rules == null ? Collections.<Rule>emptyList()
                : Collections.unmodifiableList(new ArrayList<Rule>(rules));
        this.fallback = fallback;
    }

    /**
     * 单条规则: <b>话术在前, 关键词在后</b>.
     *
     * <p>例: {@code rule("我帮你查天气", "天气", "气温")}
     */
    public static Rule rule(String reply, String... keywords) {
        List<String> ks = new ArrayList<String>();
        if (keywords != null) {
            ks.addAll(Arrays.asList(keywords));
        }
        return new Rule(ks, reply);
    }

    /** 单关键词规则: {@code keyword("天气", "我帮你查天气")}. */
    public static Rule keyword(String keyword, String reply) {
        return new Rule(Collections.singletonList(keyword), reply);
    }

    @Override
    public String name() {
        return "rule-based";
    }

    @Override
    public List<Model> listModels() {
        return Collections.singletonList(new Model("rule-based", "规则应答(零模型)", "kernel",
                Collections.<Model.Capability>emptyList(), 0L, 0L));
    }

    @Override
    public boolean supportsModel(String modelId) {
        return true;
    }

    @Override
    public Map<String, Object> providerParams() {
        return Collections.emptyMap();
    }

    /** 规则命中情况(诊断/测试用): 返回命中的那条规则, 没命中返回 null. */
    public Rule match(List<Msg> messages) {
        String text = lastUserText(messages);
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase();
        for (Rule r : rules) {
            for (String kw : r.keywords) {
                if (kw != null && !kw.isEmpty() && lower.contains(kw.toLowerCase())) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 命中规则的话术, 没命中返回 fallback. */
    public String reply(List<Msg> messages) {
        Rule r = match(messages);
        return r == null ? fallback : r.reply();
    }

    @Override
    public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
        String text = reply(request == null ? null : request.getMessages());
        List<ChatCompletionsResponse.Choice> choices = Collections.singletonList(
                new ChatCompletionsResponse.Choice(0, text, null, "stop"));
        return new ChatCompletionsResponse("rule-" + System.nanoTime(),
                request == null ? "rule-based" : request.getModel(),
                choices, TokenUsage.empty(), "stop", Collections.<String, Object>emptyMap());
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
        try {
            String text = reply(request == null ? null : request.getMessages());
            String model = request == null ? "rule-based" : request.getModel();
            for (String piece : splitByPunctuation(text)) {
                List<ChatCompletionsResponse.Choice> choices = Collections.singletonList(
                        new ChatCompletionsResponse.Choice(0, piece, null, null));
                onChunk.accept(new ChatCompletionsResponse("rule-stream",
                        model, choices, TokenUsage.empty(), null, Collections.<String, Object>emptyMap()));
            }
            List<ChatCompletionsResponse.Choice> last = Collections.singletonList(
                    new ChatCompletionsResponse.Choice(0, "", null, "stop"));
            onChunk.accept(new ChatCompletionsResponse("rule-stream",
                    model, last, TokenUsage.empty(), "stop", Collections.<String, Object>emptyMap()));
            if (onComplete != null) {
                onComplete.run();
            }
        } catch (RuntimeException e) {
            onError.accept(e);
        }
    }

    /**
     * 句读切分符 —— <b>半角与全角都要</b>.
     *
     * <p>⚠️ 本仓的话术是中文, 句读用的是全角 {@code ，。？！；};
     * 只认半角的话, 一整句中文会被当成一块吐出去, "模拟流式"就名存实亡了.
     */
    private static final String SENTENCE_ENDERS = ",.?!;\uFF0C\u3002\uFF1F\uFF01\uFF1B";

    /** 按句读切成小块, 模拟真流式的吐字节奏. */
    static List<String> splitByPunctuation(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);
            if (SENTENCE_ENDERS.indexOf(c) >= 0) {
                out.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString());
        }
        return out;
    }

    private static String lastUserText(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m != null && m.getRole() == MessageRole.USER && m.getContent() != null) {
                return m.getContent();
            }
        }
        return null;
    }
}
