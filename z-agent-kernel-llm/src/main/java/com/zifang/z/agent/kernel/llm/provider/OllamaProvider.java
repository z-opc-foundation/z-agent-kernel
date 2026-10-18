package com.zifang.z.agent.kernel.llm.provider;

import com.zifang.z.agent.kernel.llm.Model;
import okhttp3.Headers;

import java.util.Collections;
import java.util.List;

/**
 * Ollama provider. 走 OpenAI 兼容协议 ({@code /v1/chat/completions}), 继承 OpenAIProvider
 * 复用消息格式转换与 SSE 解析.
 *
 * <p>与公有云 provider 的两点差异:
 * <ul>
 *   <li>默认 base URL 是本机 Ollama 守护进程的 {@code http://localhost:11434/v1},
 *       注意末尾的 {@code /v1} —— kernel 的 OpenAIProvider 直接把 baseUrl 当 API 前缀,
 *       不像某些实现那样再拼路径.</li>
 *   <li>本地实例不校验 API key, 所以 key 为空时不再发 Authorization 头
 *       (发了 {@code Bearer null} 在部分版本上会被拒).</li>
 * </ul>
 *
 * <p>{@link #listModels()} 返回空: Ollama 的模型目录是**宿主机本地**的 (ollama pull 决定),
 * 各环境差异极大且运行期可变, 在库侧写死一份静态清单只会是错的. 调用方应显式配置 model id;
 * 真要枚举请直接问宿主机的 {@code GET /v1/models}.
 */
public class OllamaProvider extends OpenAIProvider {

    public static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";

    private final String apiKey;

    public OllamaProvider() {
        this(null, DEFAULT_BASE_URL);
    }

    public OllamaProvider(String apiBase) {
        this(null, apiBase);
    }

    public OllamaProvider(String apiKey, String apiBase) {
        super(apiKey, apiBase == null || apiBase.isEmpty() ? DEFAULT_BASE_URL : apiBase);
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public List<Model> listModels() {
        return Collections.emptyList();
    }

    /**
     * Ollama 的 model id 是 {@code name:tag} 形态 (如 {@code qwen3:30b}、{@code glm-5.3-flash:latest}),
     * 没有厂商前缀, 所以不能用 startsWith 判定归属 —— 凡是带 tag 的都归本 provider.
     */
    @Override
    public boolean supportsModel(String modelId) {
        if (modelId == null || modelId.isEmpty()) return false;
        int colon = modelId.indexOf(':');
        return colon > 0 && colon < modelId.length() - 1;
    }

    @Override
    protected Headers authHeaders() {
        Headers.Builder builder = new Headers.Builder().add("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.add("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }
}
