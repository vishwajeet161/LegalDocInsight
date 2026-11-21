package com.legaldocinsight.document_service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

@Component
public class AnalysisServiceClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String token;

    public AnalysisServiceClient(ObjectMapper json, @Value("${analysis.service-url:http://localhost:8082}") String baseUrl,
                                 @Value("${analysis.internal-token:}") String token) {
        this.json = json;
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public ResponseEntity<JsonNode> report(String documentId) {
        if (token.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        try {
            String id = UUID.fromString(documentId).toString();
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/analyses/documents/" + id + "/report"))
                .timeout(Duration.ofSeconds(5)).header("X-Internal-Token", token).GET().build();
            var response = http.send(request, info -> new LimitedBody());
                if (response.statusCode() == 404) return ResponseEntity.notFound().build();
                if (response.statusCode() == 202) return ResponseEntity.accepted().build();
                if (response.statusCode() != 200) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
                JsonNode report = json.readTree(response.body());
                if (report == null || !report.isObject()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
                return ResponseEntity.ok(report);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 1_000_000 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("Report exceeds size limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
