package com.legaldocinsight.analysis_service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:scheduling;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=none", "analysis.worker.enabled=true", "analysis.worker.poll-delay-ms=100", "analysis.model.name=synthetic"})
class AnalysisSchedulingIntegrationTest {
    private static HttpServer server;
    @Autowired JdbcTemplate db;

    @DynamicPropertySource static void modelEndpoint(DynamicPropertyRegistry registry) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            byte[] body = "{\"response\":\"{\\\"summary\\\":{\\\"text\\\":\\\"Synthetic\\\",\\\"evidence\\\":[\\\"p1\\\"]},\\\"clauses\\\":[],\\\"obligations\\\":[],\\\"risks\\\":[],\\\"lawObservations\\\":[]}\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        registry.add("analysis.model.url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @Test void enabledWorkerProcessesPersistedJobWithControlledModelEndpoint() {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO analysis_jobs (id, document_id, content_hash, analysis_version, extracted_text, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", id, UUID.randomUUID().toString(), "a".repeat(64), "v1", "Synthetic contract", "PENDING", Timestamp.from(Instant.now()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("COMPLETED", db.queryForObject("SELECT status FROM analysis_jobs WHERE id=?", String.class, id)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM analysis_reports WHERE job_id=?", Integer.class, id));
    }
}
