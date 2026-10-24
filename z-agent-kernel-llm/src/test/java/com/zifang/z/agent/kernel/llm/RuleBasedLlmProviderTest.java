package com.zifang.z.agent.kernel.llm;

import org.junit.jupiter.api.Test;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleBasedLlmProvider} 契约测试 —— 验证"不用大模型也能跑语音回路".
 *
 * <p>关注点有三个:
 * <ol>
 *   <li>关键词命中 → 话术正确(规则应答器存在的全部意义);</li>
 *   <li>没命中 → fallback, 而不是抛异常(语音回路不能因为没听懂就断掉);</li>
 *   <li>流式按<b>中文句读</b>切块 —— 半角标点切不了中文, 那样"模拟流式"名存实亡.</li>
 * </ol>
 */
class RuleBasedLlmProviderTest {

    /**
     * 中文标点用 Unicode 转义写死 —— 源码里出现过"全角逗号被写成半角"的坑,
     * 测试里再踩一次就是自己骗自己.
     */
    private static final String CN_COMMA = "，";
    private static final String CN_STOP = "。";
    private static final String CN_QUESTION = "？";

    private static List<Msg> ask(String text) {
        return Arrays.asList(new Msg(MessageRole.USER, text));
    }

    private static RuleBasedLlmProvider provider() {
        return new RuleBasedLlmProvider(
                Arrays.asList(
                        RuleBasedLlmProvider.rule("我帮你查一下天气,请告诉我城市名。", "天气", "气温"),
                        RuleBasedLlmProvider.rule("转人工请说转人工。", "转人工", "人工")),
                "我没听清,换个说法试试吧。");
    }

    @Test
    void 命中关键词返回对应话术() {
        RuleBasedLlmProvider p = provider();
        assertEquals("我帮你查一下天气,请告诉我城市名。", p.reply(ask("今天天气怎么样?")));
        assertEquals("转人工请说转人工。", p.reply(ask("我要转人工")));
    }

    @Test
    void 多关键词任一命中即可() {
        RuleBasedLlmProvider p = provider();
        assertNotNull(p.match(ask("现在气温多少")));
        assertNotNull(p.match(ask("天气怎么样")));
    }

    @Test
    void 没命中返回fallback而不是抛异常() {
        RuleBasedLlmProvider p = provider();
        assertEquals("我没听清,换个说法试试吧。", p.reply(ask("帮我写一首诗")));
        assertNull(p.match(ask("帮我写一首诗")), "match 应返回 null");
    }

    @Test
    void 关键词匹配大小写不敏感() {
        RuleBasedLlmProvider p = new RuleBasedLlmProvider(
                Arrays.asList(RuleBasedLlmProvider.keyword("Weather", "今天有雨。")),
                "fallback");
        assertEquals("今天有雨。", p.reply(ask("what is the WEATHER today")));
    }

    @Test
    void 只看最后一条用户消息() {
        RuleBasedLlmProvider p = provider();
        List<Msg> msgs = Arrays.asList(
                new Msg(MessageRole.USER, "天气"),
                new Msg(MessageRole.ASSISTANT, "你想问天气吗?"),
                new Msg(MessageRole.USER, "帮我写一首诗"));
        assertEquals("我没听清,换个说法试试吧。", p.reply(msgs), "最后一条用户消息没命中就不该回前面的规则");
    }

    @Test
    void 流式按中文句读切块且末尾有结束块() {
        RuleBasedLlmProvider p = new RuleBasedLlmProvider(
                Arrays.asList(RuleBasedLlmProvider.keyword("天气",
                        "今天天气晴朗" + CN_COMMA + "温度适中" + CN_COMMA + "适合出门" + CN_STOP)),
                "fallback");

        final List<String> pieces = new ArrayList<String>();
        final List<String> finishReasons = new ArrayList<String>();
        final boolean[] done = {false};
        final Throwable[] err = {null};

        p.streamChat(new ChatCompletionsRequest("m", ask("天气")),
                chunk -> {
                    pieces.add(chunk.getChoices().get(0).getContent());
                    finishReasons.add(chunk.getFinishReason());
                },
                t -> err[0] = t,
                () -> done[0] = true);

        assertNull(err[0], "不该有错误：" + err[0]);
        assertTrue(done[0], "流结束必须回调 onComplete");
        assertEquals(4, pieces.size(), "3 句 + 1 个结束块, 实际：" + pieces);
        assertEquals("今天天气晴朗" + CN_COMMA, pieces.get(0));
        assertEquals("温度适中" + CN_COMMA, pieces.get(1));
        assertEquals("适合出门" + CN_STOP, pieces.get(2));
        assertEquals("", pieces.get(3), "最后一块是空内容 + finishReason=stop");
        assertEquals("stop", finishReasons.get(3));

        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            joined.append(pieces.get(i));
        }
        assertEquals("今天天气晴朗" + CN_COMMA + "温度适中" + CN_COMMA + "适合出门" + CN_STOP,
                joined.toString(), "切块拼回去必须与原文逐字一致");
    }

    @Test
    void 流式也切半角标点() {
        RuleBasedLlmProvider p = new RuleBasedLlmProvider(
                Arrays.asList(RuleBasedLlmProvider.keyword("x", "a,b.c!")),
                "fallback");
        final List<String> pieces = new ArrayList<String>();
        p.streamChat(new ChatCompletionsRequest("m", ask("x")),
                chunk -> pieces.add(chunk.getChoices().get(0).getContent()),
                t -> {
                }, () -> {
                });
        assertEquals(4, pieces.size(), "3 段 + 结束块：" + pieces);
        assertEquals("a,", pieces.get(0));
        assertEquals("b.", pieces.get(1));
        assertEquals("c!", pieces.get(2));
    }

    @Test
    void 问号也是句读() {
        RuleBasedLlmProvider p = new RuleBasedLlmProvider(
                Arrays.asList(RuleBasedLlmProvider.keyword("x", "好的" + CN_QUESTION + "还有别的吗" + CN_QUESTION)),
                "fallback");
        List<String> pieces = RuleBasedLlmProvider.splitByPunctuation(
                "好的" + CN_QUESTION + "还有别的吗" + CN_QUESTION);
        assertEquals(2, pieces.size(), "两个问号切成两块：" + pieces);
        assertEquals("好的" + CN_QUESTION, pieces.get(0));
    }

    @Test
    void 同步chat返回单条完整回复() {
        RuleBasedLlmProvider p = provider();
        ChatCompletionsRequest req = new ChatCompletionsRequest("m", ask("天气"));
        ChatCompletionsResponse resp = p.chat(req);
        assertEquals(1, resp.getChoices().size());
        assertEquals("我帮你查一下天气,请告诉我城市名。", resp.getChoices().get(0).getContent());
        assertEquals("stop", resp.getFinishReason());
        assertNotNull(resp.getUsage());
        assertEquals(0L, resp.getUsage().getTotalTokens());
    }

    @Test
    void 零模型也能被当成一个provider() {
        RuleBasedLlmProvider p = provider();
        assertEquals("rule-based", p.name());
        assertTrue(p.supportsModel("任意模型id"), "规则应答器不挑模型");
        assertFalse(p.listModels().isEmpty());
        assertEquals("rule-based", p.listModels().get(0).getId());
        assertTrue(p.providerParams().isEmpty());
    }

    @Test
    void 空规则集也能安全应答() {
        RuleBasedLlmProvider p = new RuleBasedLlmProvider(Collections.<RuleBasedLlmProvider.Rule>emptyList(), "兜底");
        assertEquals("兜底", p.reply(ask("随便说点什么")));
        assertEquals(0, RuleBasedLlmProvider.splitByPunctuation("").size());
        assertEquals(0, RuleBasedLlmProvider.splitByPunctuation(null).size());
    }
}
