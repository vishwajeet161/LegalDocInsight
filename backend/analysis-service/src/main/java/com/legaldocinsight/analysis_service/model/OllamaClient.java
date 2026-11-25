package com.legaldocinsight.analysis_service.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Ollama generate client. Never includes upstream errors or response data in exceptions. */
public final class OllamaClient {
    private final HttpClient http;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper json;
    private final boolean cloud;
    private final String apiKey;

    public OllamaClient(URI base, String model, Duration timeout, ObjectMapper json) {
        this(base, model, timeout, json, false, "");
    }

    public OllamaClient(URI base, String model, Duration timeout, ObjectMapper json, boolean cloud, String apiKey) {
        if (base == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || model == null || model.isBlank()
                || timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Invalid model configuration");
        if (cloud && (apiKey == null || apiKey.isBlank() || !apiKey.equals(apiKey.trim())
                || apiKey.chars().anyMatch(Character::isISOControl)
                || !("https".equals(base.getScheme()) || "127.0.0.1".equals(base.getHost()) || "::1".equals(base.getHost()))))
            throw new IllegalArgumentException("Invalid cloud model configuration");
        this.endpoint = URI.create(base.toString().replaceAll("/+$", "") + "/api/generate");
        this.model = model;
        this.timeout = timeout;
        this.json = json;
        this.cloud = cloud;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JsonNode generate(String prompt) throws Exception {
        String body = json.writeValueAsString(cloud
                ? Map.of("model", model, "prompt", prompt, "stream", false)
                : Map.of("model", model, "prompt", prompt, "stream", false, "format", "json"));
        var builder = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json");
        if (cloud) builder.header("Authorization", "Bearer " + apiKey);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var pending = http.sendAsync(request, info -> new LimitedBody());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IllegalStateException("Model unavailable");
            JsonNode outer = json.readTree(response.body());
            if (outer == null || !outer.path("response").isTextual()) throw new IllegalStateException("Invalid model response");
            JsonNode result = json.readTree(outer.path("response").asText());
            if (result == null || !result.isObject()) throw new IllegalStateException("Invalid model response");
            return result;
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 1_000_000 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("Model response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
