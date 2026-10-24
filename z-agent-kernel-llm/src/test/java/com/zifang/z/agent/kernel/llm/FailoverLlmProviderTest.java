package com.zifang.z.agent.kernel.llm;

import org.junit.jupiter.api.Test;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link FailoverLlmProvider} 契约测试.
 *
 * <p>这个类的全部价值在**切换语义**上, 所以这里钉的四条是:
 * <ol>
 *   <li>没吐内容就失败 ⇒ 换下一家;</li>
 *   <li>已经吐了内容再失败 ⇒ <b>不换</b>, 把错误抛给上层(换了会把两家的半句话拼在一起);</li>
 *   <li>连续失败到阈值 ⇒ 熔断跳过, 成功一次即清零;</li>
 *   <li>切换时用下一家自己的 model id.</li>
 * </ol>
 */
class FailoverLlmProviderTest {

    /** 可编程的假 provider. */
    private static final class Stub implements LlmProvider {
        final String id;
        RuntimeException chatError;
        RuntimeException streamErrorBeforeOutput;
        RuntimeException streamErrorAfterOutput;
        /** 流的第一个 chunk 的内容(默认非空); 置空串可模拟"只带 role 不带 content"的 chunk. */
        String chunkContent;
        int chatCalls;
        int streamCalls;
        String seenModel;

        Stub(String id) {
            this.id = id;
            this.chunkContent = id + "-chunk";
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public List<Model> listModels() {
            return Collections.emptyList();
        }

        @Override
        public boolean supportsModel(String modelId) {
            return true;
        }

        @Override
        public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            chatCalls++;
            seenModel = request.getModel();
            if (chatError != null) {
                throw chatError;
            }
            return response(id + "-reply", request.getModel());
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
            streamCalls++;
            seenModel = request.getModel();
            if (streamErrorBeforeOutput != null) {
                onError.accept(streamErrorBeforeOutput);
                return;
            }
            onChunk.accept(response(chunkContent, request.getModel()));
            if (streamErrorAfterOutput != null) {
                onError.accept(streamErrorAfterOutput);
                return;
            }
            onComplete.run();
        }
    }

    private static ChatCompletionsResponse response(String content, String model) {
        List<ChatCompletionsResponse.Choice> choices = Collections.singletonList(
                new ChatCompletionsResponse.Choice(0, content, null, "stop"));
        return new ChatCompletionsResponse("id-" + content, model, choices,
                com.zifang.z.agent.kernel.types.TokenUsage.empty(), "stop",
                Collections.<String, Object>emptyMap());
    }

    private static ChatCompletionsRequest request() {
        return new ChatCompletionsRequest("origin-model",
                Arrays.asList(new Msg(MessageRole.USER, "你好")));
    }

    private static String contentOf(ChatCompletionsResponse resp) {
        return resp.getChoices().get(0).getContent();
    }

    @Test
    void 第一个可用就用第一个() {
        Stub a = new Stub("a");
        Stub b = new Stub("b");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, null),
                        FailoverLlmProvider.entry("b", b, null)));

        ChatCompletionsResponse resp = chain.chat(request());
        assertEquals("a-reply", contentOf(resp));
        assertEquals(1, a.chatCalls);
        assertEquals(0, b.chatCalls, "第一家正常就不该碰第二家");
    }

    @Test
    void 没吐内容就失败会切到下一家() {
        Stub a = new Stub("a");
        a.chatError = new IllegalStateException("connect refused");
        Stub b = new Stub("b");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, null),
                        FailoverLlmProvider.entry("b", b, null)));

        ChatCompletionsResponse resp = chain.chat(request());
        assertEquals("b-reply", contentOf(resp), "连接类故障必须自动接管");
        assertEquals(1, a.chatCalls);
        assertEquals(1, b.chatCalls);
    }

    @Test
    void 流式未吐内容失败会切换() {
        Stub a = new Stub("a");
        a.streamErrorBeforeOutput = new IllegalStateException("429 rate limited");
        Stub b = new Stub("b");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, null),
                        FailoverLlmProvider.entry("b", b, null)));

        final List<String> chunks = new ArrayList<String>();
        final Throwable[] err = {null};
        final boolean[] done = {false};
        chain.streamChat(request(),
                chunk -> chunks.add(contentOf(chunk)),
                t -> err[0] = t,
                () -> done[0] = true);

        assertNull(err[0], "不该有错误：" + err[0]);
        assertTrue(done[0], "切到第二家后应当正常收尾");
        assertEquals(Arrays.asList("b-chunk"), chunks);
        assertEquals(1, a.streamCalls, "第一家确实被撞过一次");
        assertEquals(1, b.streamCalls);
    }

    @Test
    void 流式已吐内容失败不切换() {
        Stub a = new Stub("a");
        a.streamErrorAfterOutput = new IllegalStateException("connection reset");
        Stub b = new Stub("b");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, null),
                        FailoverLlmProvider.entry("b", b, null)));

        final List<String> chunks = new ArrayList<String>();
        final Throwable[] err = {null};
        chain.streamChat(request(),
                chunk -> chunks.add(contentOf(chunk)),
                t -> err[0] = t,
                () -> {
                });

        assertNotNull(err[0], "已经吐过内容, 失败必须抛给上层");
        assertEquals(Arrays.asList("a-chunk"), chunks, "不能把第二家的内容拼上来");
        assertEquals(0, b.streamCalls, "已产出内容后不许切换");
    }

    @Test
    void 空chunk不算已产出_失败仍然可以切换() {
        // OpenAI 兼容协议的第一个 chunk 通常只有 role 没有 content; Ollama 连不上时也常这样.
        // 若把这种空 chunk 当成"已产出内容", 就永远切不了下一家 —— 而此时切恰恰最该切.
        Stub a = new Stub("a");
        a.chunkContent = "";
        a.streamErrorAfterOutput = new IllegalStateException("connection reset");
        Stub b = new Stub("b");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, null),
                        FailoverLlmProvider.entry("b", b, null)));

        final List<String> chunks = new ArrayList<String>();
        final Throwable[] err = {null};
        chain.streamChat(request(),
                chunk -> chunks.add(contentOf(chunk)),
                t -> err[0] = t,
                () -> {
                });

        assertNull(err[0], "空 chunk 之后失败应当切到第二家，不该报错：" + err[0]);
        assertEquals(1, b.streamCalls, "第二家应当被接上");
        assertEquals(Arrays.asList("", "b-chunk"), chunks);
    }

    @Test
    void 连续失败到阈值后熔断跳过() {
        Stub a = new Stub("a");
        a.chatError = new IllegalStateException("down");
        Stub b = new Stub("b");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, null),
                        FailoverLlmProvider.entry("b", b, null)), 2, 60_000L);

        chain.chat(request());
        chain.chat(request());
        assertEquals(Arrays.asList("a"), chain.skippedNow(), "连续失败 2 次应当熔断");

        chain.chat(request());
        assertEquals(2, a.chatCalls, "熔断期间不该再撞第一家");
        assertEquals(3, b.chatCalls);
    }

    @Test
    void 成功一次即清零失败计数() {
        Stub a = new Stub("a");
        a.chatError = new IllegalStateException("down");
        Stub b = new Stub("b");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, null),
                        FailoverLlmProvider.entry("b", b, null)), 2, 60_000L);

        chain.chat(request());
        assertTrue(chain.skippedNow().isEmpty(), "只失败 1 次不该熔断");

        a.chatError = null;
        chain.chat(request());
        assertEquals(2, a.chatCalls);

        a.chatError = new IllegalStateException("down");
        chain.chat(request());
        assertTrue(chain.skippedNow().isEmpty(), "中间成功过一次, 失败计数应已清零");
    }

    @Test
    void 切换时用下一家自己的模型id() {
        Stub a = new Stub("a");
        a.chatError = new IllegalStateException("down");
        Stub b = new Stub("b");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, "qwen2.5:7b"),
                        FailoverLlmProvider.entry("b", b, "MiniMax-M3.1-Flash")));

        chain.chat(request());
        assertEquals("qwen2.5:7b", a.seenModel, "第一家用自己的模型 id");
        assertEquals("MiniMax-M3.1-Flash", b.seenModel, "第二家必须换成自己的模型 id");
    }

    @Test
    void 全家不可用时把原始错误抛出来() {
        Stub a = new Stub("a");
        a.chatError = new IllegalStateException("全部厂商都挂了");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, null)));

        try {
            chain.chat(request());
            fail("应当抛异常");
        } catch (RuntimeException e) {
            assertEquals("全部厂商都挂了", e.getMessage());
        }
    }

    @Test
    void 空链给出可读的异常() {
        FailoverLlmProvider chain = new FailoverLlmProvider(Collections.<FailoverLlmProvider.Entry>emptyList());
        assertTrue(chain.names().isEmpty());
        try {
            chain.chat(request());
            fail("应当抛异常");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("均不可用"), "异常要说清是" + e.getMessage());
        }
    }

    @Test
    void 支持任意模型并汇总各家模型列表() {
        Stub a = new Stub("a");
        Stub b = new Stub("b");
        FailoverLlmProvider chain = new FailoverLlmProvider(
                Arrays.asList(FailoverLlmProvider.entry("a", a, null),
                        FailoverLlmProvider.entry("b", b, null)));

        assertTrue(chain.supportsModel("whatever"));
        assertEquals(Arrays.asList("a", "b"), chain.names());
        assertFalse(chain.name().isEmpty());
        assertTrue(chain.entryAt(1).provider() == b, "entryAt 要能取回原实现");
        assertEquals("b", chain.entryAt(1).name());
    }
}
