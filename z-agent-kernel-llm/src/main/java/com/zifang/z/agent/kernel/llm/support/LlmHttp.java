package com.zifang.z.agent.kernel.llm.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * HTTP + JSON 公共工具. provider 基类共享.
 *
 * <p>postJson: 同步 POST JSON, 返回响应 body 字符串.
 * <p>postJsonStream: SSE 流式 POST JSON, 每个 data 行回调 onDataLine, 失败/连接断开回调 onError.
 * <p>parseJson: 把 body 字符串 parse 成目标类型.
 */
public class LlmHttp {

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper json;

    public LlmHttp() {
        this(30, 300, 60);
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

    /**
     * SSE 流式 POST JSON. 每个 data 行回调 onDataLine, 上游可解析为 ChatCompletionsResponse chunk.
     *
     * @param onDataLine 接收每个 SSE data 行 (已剥离 "data: " 前缀, 含 "[DONE]" 行)
     * @param onError    接收 I/O / HTTP 错误 / 解析错误
     * @param onComplete 流正常关闭时回调 (可空)
     */
    public void postJsonStream(String url, okhttp3.Headers headers, Object body,
                               Consumer<String> onDataLine,
                               Consumer<Throwable> onError,
                               Runnable onComplete) {
        try {
            String bodyJson = json.writeValueAsString(body);
            Request req = new Request.Builder()
                    .url(url)
                    .headers(headers)
                    .post(RequestBody.create(bodyJson, JSON))
                    .build();
            EventSource.Factory factory = EventSources.createFactory(client);
            factory.newEventSource(req, new EventSourceListener() {
                @Override
                public void onEvent(EventSource es, String id, String type, String data) {
                    try {
                        onDataLine.accept(data);
                    } catch (Throwable t) {
                        onError.accept(t);
                    }
                }

                @Override
                public void onClosed(EventSource es) {
                    if (onComplete != null) {
                        try { onComplete.run(); } catch (Throwable ignored) { }
                    }
                }

                @Override
                public void onFailure(EventSource es, Throwable t, Response resp) {
                    if (resp != null) {
                        try {
                            ResponseBody rb = resp.body();
                            String text = rb == null ? "" : rb.string();
                            LlmException ex = new LlmException(url, resp.code(), text);
                            onError.accept(ex);
                            return;
                        } catch (IOException ioe) {
                            onError.accept(new LlmException(url, "stream failure body read", ioe));
                            return;
                        }
                    }
                    onError.accept(t == null ? new LlmException(url, "stream closed without response") : t);
                }
            });
        } catch (Exception e) {
            onError.accept(new LlmException(url, "postJsonStream setup failed", e));
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