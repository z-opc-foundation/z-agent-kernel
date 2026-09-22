package com.zifang.z.agent.kernel.llm.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * HTTP + JSON 公共工具. provider 基类共享.
 *
 * <p>postJson: 同步 POST JSON, 返回响应 body 字符串.
 * <p>parseJson: 把 body 字符串 parse 成目标类型.
 */
public class LlmHttp {

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper json;

    public LlmHttp() {
        this(30, 120, 60);
    }

    public LlmHttp(int connectTimeoutSec, int readTimeoutSec, int writeTimeoutSec) {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
                .build();
        this.json = new ObjectMapper();
    }

    public OkHttpClient client() {
        return client;
    }

    public ObjectMapper json() {
        return json;
    }

    public String postJson(String url, okhttp3.Headers headers, Object body) {
        try {
            String bodyJson = json.writeValueAsString(body);
            Request req = new Request.Builder()
                    .url(url)
                    .headers(headers)
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                ResponseBody rb = resp.body();
                String text = rb == null ? "" : rb.string();
                if (!resp.isSuccessful()) {
                    throw new LlmException(url, resp.code(), text);
                }
                return text;
            }
        } catch (IOException e) {
            throw new LlmException(url, "postJson I/O failed", e);
        }
    }

    public <T> T parseJson(String raw, Class<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (IOException e) {
            throw new LlmException("parseJson", "Failed to parse " + type.getSimpleName(), e);
        }
    }
}