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
}
