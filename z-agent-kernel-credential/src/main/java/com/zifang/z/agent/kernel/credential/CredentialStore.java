package com.zifang.z.agent.kernel.credential;

import java.util.Map;

/**
 * CredentialStore SPI — 密钥/凭证存储.
 *
 * <p>实现方: z-mist (生产级凭证管理: 加密 / 轮换 / 审计).
 *
 * <p>为 agent 提供按 key 取明文凭证的统一入口.
 */
public interface CredentialStore {

    /**
     * 取一个凭证的明文.
     *
     * @param key 凭证标识(如 "openai.api_key" / "anthropic.api_key")
     * @return 明文凭证(API Key / Token / 证书 等)
     * @throws CredentialNotFoundException 凭证不存在或被禁用
     */
    String get(String key);

    /**
     * 列出所有凭证 key(不返回明文).
     */
    Map<String, String> list();

    /**
     * @return 是否加密落盘
     */
    default boolean isEncrypted() {
        return false;
    }

    class CredentialNotFoundException extends RuntimeException {
        public CredentialNotFoundException(String key) {
            super("credential not found: " + key);
        }
    }
}