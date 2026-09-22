package com.zifang.z.agent.kernel.llm.provider;

import com.zifang.z.agent.kernel.llm.Model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 通义千问 (Qwen) provider. 走阿里云 DashScope 的 OpenAI 兼容模式 (兼容模式端点 /compatible-mode/v1/chat/completions),
 * 继承 OpenAIProvider 复用消息格式转换.
 *
 * <p>默认 base URL: https://dashscope.aliyuncs.com/compatible-mode/v1
 */
public class QwenProvider extends OpenAIProvider {

    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    public QwenProvider(String apiKey) {
        super(apiKey, DEFAULT_BASE_URL);
    }

    public QwenProvider(String apiKey, String apiBase) {
        super(apiKey, apiBase == null || apiBase.isEmpty() ? DEFAULT_BASE_URL : apiBase);
    }

    @Override
    public String name() {
        return "qwen";
    }

    @Override
    public List<Model> listModels() {
        List<Model> out = new ArrayList<>();
        out.add(new Model("qwen-max", "Qwen Max", "qwen",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.JSON_MODE),
                128000L, 8192L));
        out.add(new Model("qwen-plus", "Qwen Plus", "qwen",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.TOOLS, Model.Capability.JSON_MODE),
                128000L, 8192L));
        out.add(new Model("qwen-turbo", "Qwen Turbo", "qwen",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.JSON_MODE),
                128000L, 8192L));
        out.add(new Model("qwen-long", "Qwen Long", "qwen",
                Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM),
                1000000L, 6000L));
        return out;
    }

    @Override
    public boolean supportsModel(String modelId) {
        if (modelId == null) return false;
        return modelId.toLowerCase().startsWith("qwen-");
    }
}