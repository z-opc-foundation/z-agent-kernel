package com.zifang.z.agent.kernel.llm.provider;

import com.zifang.z.agent.kernel.llm.Model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DeepSeek provider. 走 OpenAI 兼容协议, 继承 OpenAIProvider 复用消息格式转换.
 *
 * <p>默认 base URL: https://api.deepseek.com/v1
 */
public class DeepSeekProvider extends OpenAIProvider {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";

    public DeepSeekProvider(String apiKey) {
        super(apiKey, DEFAULT_BASE_URL);
    }

    public DeepSeekProvider(String apiKey, String apiBase) {
        super(apiKey, apiBase == null || apiBase.isEmpty() ? DEFAULT_BASE_URL : apiBase);
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public List<Model> listModels() {
        List<Model> out = new ArrayList<>();
        out.add(new Model("deepseek-chat", "DeepSeek-V3 Chat", "deepseek",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.JSON_MODE),
                64000L, 8192L));
        out.add(new Model("deepseek-reasoner", "DeepSeek-R1 Reasoner", "deepseek",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS),
                64000L, 8192L));
        return out;
    }

    @Override
    public boolean supportsModel(String modelId) {
        if (modelId == null) return false;
        return modelId.toLowerCase().startsWith("deepseek-");
    }
}