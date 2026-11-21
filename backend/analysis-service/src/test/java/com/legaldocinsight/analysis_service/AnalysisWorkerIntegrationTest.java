package com.legaldocinsight.analysis_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legaldocinsight.analysis_service.model.AnalysisWorker;
import com.legaldocinsight.analysis_service.model.AnalysisDispatcher;
import com.legaldocinsight.analysis_service.persistence.AnalysisReportStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:worker;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=none", "analysis.internal-token=synthetic-test-credential-32-bytes-long"})
@AutoConfigureMockMvc
class AnalysisWorkerIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired AnalysisReportStore reports;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    @Test void processesPendingJobAndStoresEvidenceAndCoverage() throws Exception {
        String job = addJob();
        var worker = new AnalysisWorker(db, reports, json, prompt -> json.readTree("{\"summary\":{\"text\":\"Synthetic summary\",\"evidence\":[\"p1\"]},\"clauses\":[],\"obligations\":[],\"risks\":[],\"lawObservations\":[]}"), "synthetic-model", "v1", 100, 1);
        assertTrue(worker.process(job));
        assertEquals("COMPLETED", db.queryForObject("SELECT status FROM analysis_jobs WHERE id=?", String.class, job));
        var report = json.readTree(db.queryForObject("SELECT report_json FROM analysis_reports WHERE job_id=?", String.class, job));
        assertEquals("Synthetic summary", report.path("summary").asText());
        assertEquals("p1", report.path("evidenceReferences").get(0).path("id").asText());
        assertEquals(18, report.path("coverage").path("analyzedCharacters").asInt());
        assertFalse(worker.process(job));
    }

    @Test void invalidEvidenceFailsWithoutPersistingReport() throws Exception {
        String job = addJob();
        var worker = new AnalysisWorker(db, reports, json, prompt -> json.readTree("{\"summary\":{\"text\":\"Invented\",\"evidence\":[\"p99\"]},\"clauses\":[],\"obligations\":[],\"risks\":[],\"lawObservations\":[]}"), "synthetic-model", "v1", 100, 1);
        assertTrue(worker.process(job));
        assertEquals("FAILED", db.queryForObject("SELECT status FROM analysis_jobs WHERE id=?", String.class, job));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM analysis_reports WHERE job_id=?", Integer.class, job));
    }

    @Test void concurrentClaimsMakeOnlyOneModelCall() throws Exception {
        String job = addJob();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var worker = new AnalysisWorker(db, reports, json, prompt -> {
            calls.incrementAndGet();
            Thread.sleep(100);
            return json.readTree("{\"summary\":{\"text\":\"Synthetic\",\"evidence\":[\"p1\"]},\"clauses\":[],\"obligations\":[],\"risks\":[],\"lawObservations\":[]}");
        }, "synthetic-model", "v1", 100, 1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return worker.process(job); });
            var second = executor.submit(() -> { start.await(); return worker.process(job); });
            start.countDown();
            assertNotEquals(first.get(), second.get());
            assertEquals(1, calls.get());
            assertEquals(1, db.queryForObject("SELECT count(*) FROM analysis_reports WHERE job_id=?", Integer.class, job));
        } finally { executor.shutdownNow(); }
    }

    @Test void dispatcherRecoversInterruptedWorkBeforeDispatch() throws Exception {
        String job = addJob();
        db.update("UPDATE analysis_jobs SET status='PROCESSING' WHERE id=?", job);
        var worker = new AnalysisWorker(db, reports, json, prompt -> json.readTree("{\"summary\":{\"text\":\"Synthetic\",\"evidence\":[\"p1\"]},\"clauses\":[],\"obligations\":[],\"risks\":[],\"lawObservations\":[]}"), "synthetic-model", "v1", 100, 1);
        var dispatcher = new AnalysisDispatcher(db, worker);
        dispatcher.recover();
        dispatcher.dispatch();
        assertEquals("COMPLETED", db.queryForObject("SELECT status FROM analysis_jobs WHERE id=?", String.class, job));
    }

    @Test void modelFailureLeavesNoCompletedReport() {
        String job = addJob();
        var worker = new AnalysisWorker(db, reports, json, prompt -> { throw new java.io.IOException("model unavailable"); }, "synthetic-model", "v1", 100, 1);
        assertTrue(worker.process(job));
        assertEquals("PENDING", db.queryForObject("SELECT status FROM analysis_jobs WHERE id=?", String.class, job));
        assertNotNull(db.queryForObject("SELECT next_attempt_at FROM analysis_jobs WHERE id=?", java.sql.Timestamp.class, job));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM analysis_reports WHERE job_id=?", Integer.class, job));
    }

    @Test void deletingDuringModelCallPreventsReportRecreation() throws Exception {
        String job = addJob();
        String document = db.queryForObject("SELECT document_id FROM analysis_jobs WHERE id=?", String.class, job);
        var called = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var worker = new AnalysisWorker(db, reports, json, prompt -> {
            called.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return json.readTree("{\"summary\":{\"text\":\"Synthetic\",\"evidence\":[\"p1\"]},\"clauses\":[],\"obligations\":[],\"risks\":[],\"lawObservations\":[]}");
        }, "synthetic-model", "v1", 100, 1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var processing = executor.submit(() -> worker.process(job));
            assertTrue(called.await(5, java.util.concurrent.TimeUnit.SECONDS));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/internal/analyses/documents/{id}", document)
                .header("X-Internal-Token", "synthetic-test-credential-32-bytes-long"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
            release.countDown();
            assertTrue(processing.get());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM analysis_reports WHERE job_id=?", Integer.class, job));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE id=?", Integer.class, job));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private String addJob() {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO analysis_jobs (id, document_id, content_hash, analysis_version, extracted_text, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", id, UUID.randomUUID().toString(), "a".repeat(64), "v1", "Synthetic contract", "PENDING", Timestamp.from(Instant.now()));
        return id;
    }
}
