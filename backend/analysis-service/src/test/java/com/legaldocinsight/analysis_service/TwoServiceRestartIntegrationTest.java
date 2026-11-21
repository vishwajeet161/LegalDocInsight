package com.legaldocinsight.analysis_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legaldocinsight.analysis_service.persistence.AnalysisReportStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in synthetic HTTP check; requires a dedicated PostgreSQL database and document-service jar. */
@EnabledIfSystemProperty(named = "test.two-service", matches = "true")
@ContextConfiguration(initializers = TwoServiceRestartIntegrationTest.DedicatedDatabase.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=${TEST_ANALYSIS_DB_URL}", "spring.datasource.username=${TEST_ANALYSIS_DB_USER}",
    "spring.datasource.password=${TEST_ANALYSIS_DB_PASSWORD}", "analysis.internal-token=synthetic-test-credential-32-bytes-long"})
class TwoServiceRestartIntegrationTest {
    static class DedicatedDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            String url = System.getenv("TEST_ANALYSIS_DB_URL");
            if (url == null || !url.matches("jdbc:postgresql://[^/]+/feature3(?:\\?.*)?")
                    || !url.equals(System.getenv("ANALYSIS_DB_URL"))) {
                throw new IllegalArgumentException("A dedicated feature3 PostgreSQL database is required");
            }
        }
    }
    private static final String SECRET = "synthetic-test-credential-32-bytes-long";
    @Autowired AnalysisReportStore fakeStore;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @TempDir Path temp;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Process analysis;
    private Process document;
    private Process gateway;

    @Test void reportSurvivesAnalysisProcessRestartAndRequiresDocumentToken() throws Exception {
        String url = System.getenv("TEST_ANALYSIS_DB_URL");
        assertTrue(url.matches("jdbc:postgresql://[^/]+/feature3(?:\\?.*)?"), "dedicated feature3 database required");
        int analysisPort = port(), documentPort = port(), gatewayPort = port();
        String analysisUrl = "http://127.0.0.1:" + analysisPort;
        String documentUrl = "http://127.0.0.1:" + documentPort;
        String gatewayUrl = "http://127.0.0.1:" + gatewayPort;
        String id = null;
        try {
            analysis = startAnalysis(analysisPort, "analysis-first.log");
            document = startDocument(documentPort, analysisUrl);
            gateway = startGateway(gatewayPort, documentUrl);
            await(analysisUrl + "/internal/analyses/documents/00000000-0000-0000-0000-000000000000/report", 401);
            await(documentUrl + "/api/documents/00000000-0000-0000-0000-000000000000/report", 404);
            await(gatewayUrl + "/actuator/health", 200);
            int publicAnalysisStatus = get(gatewayUrl + "/api/analysis/reports/example", null).statusCode();
            assertTrue(publicAnalysisStatus >= 400 && publicAnalysisStatus < 500);
            String boundary = "synthetic-boundary";
            String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.pdf\"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.4\n%%EOF\r\n--" + boundary + "--\r\n";
            var upload = send(HttpRequest.newBuilder(URI.create(gatewayUrl + "/api/documents/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(multipart)).build());
            assertEquals(202, upload.statusCode(), upload.body());
            var receipt = json.readTree(upload.body());
            id = receipt.path("documentId").asText();
            String token = receipt.path("accessToken").asText();
            String reportUrl = gatewayUrl + "/api/documents/" + id + "/report";
            assertEquals(403, get(reportUrl, null).statusCode());
            assertEquals(403, get(reportUrl, "wrong").statusCode());
            assertEquals(404, get(reportUrl, token).statusCode());
            String text = "Synthetic contract";
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            String body = json.writeValueAsString(Map.of("documentId", id, "contentHash", hash, "analysisVersion", "v1", "text", text));
            var submit = submission(analysisUrl, body);
            assertEquals(202, submit.statusCode());
            String job = json.readTree(submit.body()).path("jobId").asText();
            assertEquals(202, get(reportUrl, token).statusCode());
            // Test-only fake analyzer produces a fixed synthetic report through the real transactional store.
            fakeStore.complete(job, json.readTree("{\"schemaVersion\":\"1\",\"summary\":\"Synthetic\",\"clauses\":[],\"obligations\":[],\"risks\":[],\"evidenceReferences\":[],\"lawObservations\":[],\"modelId\":\"fake\",\"promptVersion\":\"test\"}"));
            var result = get(reportUrl, token);
            assertEquals(200, result.statusCode());
            assertEquals("no-store", result.headers().firstValue("Cache-Control").orElse(""));
            assertEquals("fake", json.readTree(result.body()).path("modelId").asText());
            stop(analysis); analysis = null;
            assertEquals(503, get(reportUrl, token).statusCode());
            analysis = startAnalysis(analysisPort, "analysis-second.log");
            await(analysisUrl + "/internal/analyses/documents/00000000-0000-0000-0000-000000000000/report", 401);
            var duplicate = submission(analysisUrl, body);
            assertEquals(202, duplicate.statusCode());
            assertEquals(job, json.readTree(duplicate.body()).path("jobId").asText());
            var after = get(reportUrl, token);
            assertEquals(200, after.statusCode());
            assertEquals(json.readTree(result.body()), json.readTree(after.body()));
            assertEquals(1, db.queryForObject("select count(*) from analysis_jobs where document_id=?", Integer.class, id));
            for (String log : java.util.List.of("gateway.log", "document.log", "analysis-first.log", "analysis-second.log", "logs/api-gateway.log", "logs/document-service.log")) {
                String contents = java.nio.file.Files.readString(temp.resolve(log));
                assertFalse(contents.contains(token), "document token appeared in " + log);
                assertFalse(contents.contains(SECRET), "internal credential appeared in " + log);
                assertFalse(contents.contains(text), "synthetic text appeared in " + log);
            }
        } finally {
            stop(gateway); stop(document); stop(analysis);
            if (id != null) {
                db.update("delete from analysis_reports where job_id in (select id from analysis_jobs where document_id=?)", id);
                db.update("delete from analysis_jobs where document_id=?", id);
                db.update("delete from documents where id=?", id);
            }
        }
    }
    private Process startAnalysis(int port, String log) throws Exception {
        return new ProcessBuilder(java(), "-cp", System.getProperty("surefire.test.class.path"), AnalysisServiceApplication.class.getName(),
            "--server.port=" + port).redirectErrorStream(true).redirectOutput(temp.resolve(log).toFile()).start();
    }
    private Process startDocument(int port, String analysisUrl) throws Exception {
        var p = new ProcessBuilder(java(), "-jar", Path.of("../document-service/target/document-service-1.0.0-SNAPSHOT.jar").toAbsolutePath().toString(), "--server.port=" + port,
            "--spring.datasource.url=" + System.getenv("TEST_ANALYSIS_DB_URL"), "--spring.datasource.username=" + System.getenv("TEST_ANALYSIS_DB_USER"),
            "--spring.jpa.hibernate.ddl-auto=update", "--storage.local.upload-dir=" + temp.resolve("files"),
            "--analysis.service-url=" + analysisUrl, "--analysis.internal-token=" + SECRET)
            .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(temp.resolve("document.log").toFile());
        p.environment().put("SPRING_DATASOURCE_PASSWORD", System.getenv("TEST_ANALYSIS_DB_PASSWORD"));
        return p.start();
    }
    private Process startGateway(int port, String documentUrl) throws Exception {
        var p = new ProcessBuilder(java(), "-jar", Path.of("../api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar").toAbsolutePath().toString(),
            "--server.port=" + port).directory(temp.toFile()).redirectErrorStream(true)
            .redirectOutput(temp.resolve("gateway.log").toFile());
        p.environment().put("DOCUMENT_SERVICE_URL", documentUrl);
        return p.start();
    }
    private HttpResponse<String> submission(String base, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + "/internal/analyses")).header("X-Internal-Token", SECRET)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }
    private HttpResponse<String> get(String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8));
        if (token != null) request.header("X-Document-Token", token);
        return send(request.GET().build());
    }
    private HttpResponse<String> send(HttpRequest request) throws Exception { return http.send(request, HttpResponse.BodyHandlers.ofString()); }
    private void await(String url, int status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            try { if (get(url, null).statusCode() == status) return; } catch (java.io.IOException ignored) { }
            Thread.sleep(200);
        }
        fail("Service did not start: " + url + "; logs: " + temp);
    }
    private static int port() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private static void stop(Process p) throws Exception { if (p != null && p.isAlive()) { p.destroyForcibly(); assertTrue(p.waitFor(10, TimeUnit.SECONDS)); } }
}
