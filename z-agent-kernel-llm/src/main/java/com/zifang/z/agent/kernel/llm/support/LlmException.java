package com.zifang.z.agent.kernel.llm.support;

/**
 * LLM 调用异常基类. 所有 provider 调用失败 (网络 / 鉴权 / 限流 / 解析 / 上游业务错误) 都抛此异常.
 */
public class LlmException extends RuntimeException {

    private final String provider;
    private final Integer httpStatus;

    public LlmException(String provider, String message) {
        super("[" + provider + "] " + message);
        this.provider = provider;
        this.httpStatus = null;
    }

    public LlmException(String provider, String message, Throwable cause) {
        super("[" + provider + "] " + message, cause);
        this.provider = provider;
        this.httpStatus = null;
    }

    public LlmException(String provider, int httpStatus, String message) {
        super("[" + provider + "] HTTP " + httpStatus + " " + message);
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public String getProvider() {
        return provider;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}