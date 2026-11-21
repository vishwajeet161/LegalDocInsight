package com.legaldocinsight.analysis_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legaldocinsight.analysis_service.model.OllamaClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class OllamaClientTest {
    @Test void timesOutWhenResponseBodyStallsAfterHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var client = new OllamaClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "model-test", Duration.ofMillis(300), new ObjectMapper());
            long start = System.nanoTime();
            assertThrows(Exception.class, () -> client.generate("synthetic prompt"));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1200);
        } finally { server.stop(0); }
    }
    @Test void requestsStructuredNonStreamingOutputAndReadsResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        server.createContext("/api/generate", exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            assertEquals("model-test", request.path("model").asText());
            assertFalse(request.path("stream").asBoolean(true));
            assertEquals("json", request.path("format").asText());
            byte[] response = "{\"response\":\"{\\\"result\\\":true}\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new OllamaClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "model-test", Duration.ofSeconds(2), json);
            assertTrue(client.generate("synthetic prompt").path("result").asBoolean());
        } finally { server.stop(0); }
    }

    @Test void rejectsInvalidModelResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            byte[] response = "{\"response\":\"not json\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new OllamaClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "model-test", Duration.ofSeconds(2), new ObjectMapper());
            assertThrows(Exception.class, () -> client.generate("synthetic prompt"));
        } finally { server.stop(0); }
    }
}
