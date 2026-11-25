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
            if (url == null || !url.matches("jdbc:postgresql://[^/]+/feature[34](?:\\?.*)?")
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

    @Test void automaticHandoffAndAnalysisRecoverAfterProcessRestart() throws Exception {
        int analysisPort = port(), documentPort = port(), gatewayPort = port();
        String analysisUrl = "http://127.0.0.1:" + analysisPort;
        String documentUrl = "http://127.0.0.1:" + documentPort;
        String gatewayUrl = "http://127.0.0.1:" + gatewayPort;
        var modelCalled = new java.util.concurrent.CountDownLatch(1);
        var releaseModel = new java.util.concurrent.CountDownLatch(1);
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        var model = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/api/generate", exchange -> {
            try {
                if (modelCalls.incrementAndGet() == 1) { modelCalled.countDown(); releaseModel.await(30, TimeUnit.SECONDS); }
                byte[] response = "{\"response\":\"{\\\"summary\\\":{\\\"text\\\":\\\"Synthetic summary\\\",\\\"evidence\\\":[\\\"p1\\\"]},\\\"clauses\\\":[],\\\"obligations\\\":[],\\\"risks\\\":[],\\\"lawObservations\\\":[]}\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { }
            finally { exchange.close(); }
        });
        model.start();
        String id = null;
        try (var scanner = new FakeScanner()) {
            analysis = startWorker(analysisPort, model.getAddress().getPort(), "worker-first.log");
            document = startDocumentWithHandoff(documentPort, analysisUrl, scanner.port());
            gateway = startGateway(gatewayPort, documentUrl);
            await(analysisUrl + "/internal/analyses/documents/00000000-0000-0000-0000-000000000000/report", 401);
            await(documentUrl + "/api/documents/00000000-0000-0000-0000-000000000000/report", 404);
            await(gatewayUrl + "/actuator/health", 200);
            byte[] docx = syntheticDocx();
            String boundary = "synthetic-boundary";
            var prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.docx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            var suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            var body = new java.io.ByteArrayOutputStream(); body.write(prefix); body.write(docx); body.write(suffix);
            var upload = send(HttpRequest.newBuilder(URI.create(gatewayUrl + "/api/documents/upload")).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build());
            assertEquals(202, upload.statusCode());
            var receipt = json.readTree(upload.body());
            id = receipt.path("documentId").asText();
            String token = receipt.path("accessToken").asText();
            String reportUrl = gatewayUrl + "/api/documents/" + id + "/report";
            assertTrue(modelCalled.await(90, TimeUnit.SECONDS), "automatic extraction and handoff did not reach the model");
            assertEquals(202, get(reportUrl, token).statusCode());
            stop(analysis); analysis = null;
            releaseModel.countDown();
            analysis = startWorker(analysisPort, model.getAddress().getPort(), "worker-second.log");
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            HttpResponse<String> report = null;
            while (System.nanoTime() < deadline) {
                try { report = get(reportUrl, token); if (report.statusCode() == 200) break; } catch (java.io.IOException ignored) { }
                Thread.sleep(200);
            }
            assertNotNull(report);
            assertEquals(200, report.statusCode(), report.body());
            assertEquals("Synthetic summary", json.readTree(report.body()).path("summary").asText());
            assertTrue(modelCalls.get() >= 2);
            assertEquals(1, db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE document_id=?", Integer.class, id));
            assertEquals(403, get(reportUrl, null).statusCode());
            String extracted = db.queryForObject("SELECT extracted_text FROM analysis_jobs WHERE document_id=?", String.class, id);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(extracted.getBytes(StandardCharsets.UTF_8)));
            String retry = json.writeValueAsString(Map.of("documentId", id, "contentHash", hash, "analysisVersion", "v1", "text", extracted));
            var delete = send(HttpRequest.newBuilder(URI.create(gatewayUrl + "/api/documents/" + id))
                .header("X-Document-Token", token).DELETE().build());
            assertEquals(204, delete.statusCode());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE document_id=?", Integer.class, id));
            assertEquals(410, submission(analysisUrl, retry).statusCode());
            for (String log : java.util.List.of("gateway.log", "document.log", "worker-first.log", "worker-second.log")) {
                String contents = java.nio.file.Files.readString(temp.resolve(log));
                assertFalse(contents.contains(token), "document token appeared in " + log);
                assertFalse(contents.contains(SECRET), "credential appeared in " + log);
                assertFalse(contents.contains("Synthetic agreement"), "document text appeared in " + log);
            }
        } finally {
            releaseModel.countDown(); model.stop(0);
            stop(gateway); stop(document); stop(analysis);
            if (id != null) {
                db.update("DELETE FROM analysis_reports WHERE job_id IN (SELECT id FROM analysis_jobs WHERE document_id=?)", id);
                db.update("DELETE FROM analysis_jobs WHERE document_id=?", id);
                db.update("DELETE FROM analysis_deleted_documents WHERE document_id=?", id);
                db.update("DELETE FROM documents WHERE id=?", id);
            }
        }
    }

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
    private Process startWorker(int port, int modelPort, String log) throws Exception {
        return new ProcessBuilder(java(), "-cp", System.getProperty("surefire.test.class.path"), AnalysisServiceApplication.class.getName(),
            "--server.port=" + port, "--analysis.worker.enabled=true", "--analysis.worker.poll-delay-ms=200",
            "--analysis.model.url=http://127.0.0.1:" + modelPort, "--analysis.model.name=synthetic")
            .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(temp.resolve(log).toFile()).start();
    }
    private Process startDocumentWithHandoff(int port, String analysisUrl, int scannerPort) throws Exception {
        var p = new ProcessBuilder(java(), "-jar", Path.of("../document-service/target/document-service-1.0.0-SNAPSHOT.jar").toAbsolutePath().toString(),
            "--server.port=" + port, "--spring.datasource.url=" + System.getenv("TEST_ANALYSIS_DB_URL"),
            "--spring.datasource.username=" + System.getenv("TEST_ANALYSIS_DB_USER"), "--spring.jpa.hibernate.ddl-auto=update",
            "--storage.local.upload-dir=" + temp.resolve("files"), "--analysis.service-url=" + analysisUrl,
            "--analysis.internal-token=" + SECRET, "--analysis.handoff.enabled=true", "--analysis.handoff.poll-delay-ms=200",
            "--document.scanner.port=" + scannerPort).directory(temp.toFile()).redirectErrorStream(true)
            .redirectOutput(temp.resolve("document.log").toFile());
        p.environment().put("SPRING_DATASOURCE_PASSWORD", System.getenv("TEST_ANALYSIS_DB_PASSWORD"));
        return p.start();
    }
    private static byte[] syntheticDocx() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            var files = Map.of("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
                "word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Synthetic agreement</w:t></w:r></w:p></w:body></w:document>");
            for (var entry : files.entrySet()) { zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    private static class FakeScanner implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0);
        private final Thread thread;
        FakeScanner() throws Exception {
            thread = new Thread(() -> {
                while (!server.isClosed()) try (var socket = server.accept()) {
                    var input = new java.io.DataInputStream(socket.getInputStream());
                    var command = new java.io.ByteArrayOutputStream();
                    int c; while ((c = input.read()) > 0) command.write(c);
                    String response;
                    if (command.toString(StandardCharsets.US_ASCII).equals("zVERSION")) {
                        String date = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", java.util.Locale.US).withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
                        response = "ClamAV 1.0/123/" + date + "\0";
                    } else {
                        int length; while ((length = input.readInt()) > 0) input.skipNBytes(length);
                        response = "stream: OK\0";
                    }
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (java.io.IOException ignored) { }
            }, "synthetic-scanner");
            thread.setDaemon(true); thread.start();
        }
        int port() { return server.getLocalPort(); }
        public void close() throws Exception { server.close(); thread.join(1000); }
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
