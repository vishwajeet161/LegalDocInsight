package com.legaldocinsight.document_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legaldocinsight.document_service.client.AnalysisServiceClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisServiceClientTest {
    @Test void submitsHashedTextWithInternalCredential() throws Exception {
        var json = new ObjectMapper();
        var received = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/analyses", exchange -> {
            assertEquals("synthetic-internal-credential-32-bytes", exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            received.set(json.readTree(exchange.getRequestBody()));
            byte[] body = ("{\"jobId\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var client = new AnalysisServiceClient(json, "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-internal-credential-32-bytes");
            String id = UUID.randomUUID().toString();
            assertEquals(HttpStatus.ACCEPTED, client.submit(id, "v1", "synthetic text").getStatusCode());
            assertEquals(id, received.get().path("documentId").asText());
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("synthetic text".getBytes(StandardCharsets.UTF_8))), received.get().path("contentHash").asText());
        } finally { server.stop(0); }
    }
    @Test void sendsInternalCredentialAndReturnsPrivateReport() throws Exception {
        var credential = "synthetic-internal-credential-32-bytes";
        var observed = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/analyses/documents", exchange -> {
            observed.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] result = "{\"summary\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, result.length);
            try (var output = exchange.getResponseBody()) { output.write(result); }
        });
        server.start();
        try {
            var client = new AnalysisServiceClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), credential);
            var response = client.report(UUID.randomUUID().toString());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("synthetic", response.getBody().path("summary").asText());
            assertEquals(credential, observed.get());
        } finally { server.stop(0); }
    }
    @Test void stalledResponseBodyIsBoundedByRequestTimeout() throws Exception {
        var sent = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/analyses/documents", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                sent.countDown();
                release.await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var client = new AnalysisServiceClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-internal-credential-32-bytes");
            var result = executor.submit(() -> client.report(UUID.randomUUID().toString()));
            assertTrue(sent.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result.get(7, java.util.concurrent.TimeUnit.SECONDS).getStatusCode());
        } finally { release.countDown(); executor.shutdownNow(); server.stop(0); }
    }
    @Test void rejectsInvalidUpstreamResponsesAndDoesNotFollowRedirects() throws Exception {
        var status = new java.util.concurrent.atomic.AtomicInteger(500);
        var body = new AtomicReference<>("sensitive upstream error");
        var redirected = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/analyses/documents", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            catch (java.io.IOException ignored) { /* oversized responses are cancelled */ }
        });
        server.createContext("/redirect-target", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            var client = new AnalysisServiceClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-internal-credential-32-bytes");
            for (var test : java.util.List.of(new Object[]{500, "sensitive upstream error"}, new Object[]{302, "redirect"},
                    new Object[]{200, "not-json"}, new Object[]{200, "[]"}, new Object[]{200, "x".repeat(1_000_001)})) {
                status.set((int) test[0]); body.set((String) test[1]);
                var response = client.report(UUID.randomUUID().toString());
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                assertNull(response.getBody());
            }
            assertEquals(0, redirected.get());
        } finally { server.stop(0); }
    }

    @Test void delayedHeadersAreBoundedByRequestTimeout() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/analyses/documents", exchange -> {
            try { release.await(15, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new AnalysisServiceClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-internal-credential-32-bytes");
            long start = System.nanoTime();
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, client.report(UUID.randomUUID().toString()).getStatusCode());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).compareTo(java.time.Duration.ofSeconds(7)) < 0);
        } finally { release.countDown(); server.stop(0); }
    }
}
