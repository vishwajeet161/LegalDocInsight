package com.legaldocinsight.analysis_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legaldocinsight.analysis_service.persistence.AnalysisReportStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=${TEST_ANALYSIS_DB_URL:jdbc:h2:mem:analysis;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}", "spring.datasource.username=${TEST_ANALYSIS_DB_USER:sa}", "spring.datasource.password=${TEST_ANALYSIS_DB_PASSWORD:}", "spring.jpa.hibernate.ddl-auto=create-drop", "analysis.internal-token=synthetic-test-credential-32-bytes-long"})
@AutoConfigureMockMvc
class AnalysisControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AnalysisReportStore reports;

    @Test void authenticatedSubmissionIsIdempotentAndRetrievableAsPending() throws Exception {
        String document = UUID.randomUUID().toString();
        String body = request(document, "Synthetic contract");
        String first = mvc.perform(post("/internal/analyses").header("X-Internal-Token", "synthetic-test-credential-32-bytes-long").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/internal/analyses").header("X-Internal-Token", "synthetic-test-credential-32-bytes-long").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(first).get("jobId"), json.readTree(second).get("jobId"));
        mvc.perform(get("/internal/analyses/documents/{id}/report", document).header("X-Internal-Token", "synthetic-test-credential-32-bytes-long"))
            .andExpect(status().isAccepted());
    }

    @Test void credentialsAndContentAreValidated() throws Exception {
        String document = UUID.randomUUID().toString();
        String body = request(document, "Synthetic contract");
        mvc.perform(post("/internal/analyses").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/analyses/documents/{id}/report", document).header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/analyses").header("X-Internal-Token", "synthetic-test-credential-32-bytes-long").contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("Synthetic contract", "tampered"))).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/analyses/documents/{id}/report", document).header("X-Internal-Token", "synthetic-test-credential-32-bytes-long"))
            .andExpect(status().isNotFound());
    }

    @Test void fakeAnalyzerPersistsCompleteReportAtomically() throws Exception {
        String document = UUID.randomUUID().toString();
        String response = mvc.perform(post("/internal/analyses").header("X-Internal-Token", "synthetic-test-credential-32-bytes-long")
            .contentType(MediaType.APPLICATION_JSON).content(request(document, "Synthetic contract")))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String job = json.readTree(response).get("jobId").asText();
        String report = json.writeValueAsString(Map.of("schemaVersion", "1", "summary", "Synthetic", "clauses", java.util.List.of(),
            "obligations", java.util.List.of(), "risks", java.util.List.of(), "evidenceReferences", java.util.List.of(),
            "lawObservations", java.util.List.of(), "modelId", "fake", "promptVersion", "test"));
        reports.complete(job, json.readTree(report));
        mvc.perform(get("/internal/analyses/documents/{id}/report", document).header("X-Internal-Token", "synthetic-test-credential-32-bytes-long"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.modelId").value("fake")).andExpect(jsonPath("$.summary").value("Synthetic"));
    }

    @Test void rejectsOversizedBodiesBeforeDeserialization() throws Exception {
        mvc.perform(post("/internal/analyses").header("X-Internal-Token", "synthetic-test-credential-32-bytes-long")
            .contentType(MediaType.APPLICATION_JSON).content("x".repeat(1_100_001))).andExpect(status().isPayloadTooLarge());
    }

    @Test void concurrentSubmissionsConvergeOnOneJob() throws Exception {
        String document = UUID.randomUUID().toString();
        String body = request(document, "Concurrent synthetic contract");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var calls = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 6; i++) calls.add(executor.submit(() -> {
                start.await();
                var result = mvc.perform(post("/internal/analyses").header("X-Internal-Token", "synthetic-test-credential-32-bytes-long")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn();
                return json.readTree(result.getResponse().getContentAsString()).get("jobId").asText();
            }));
            start.countDown();
            var ids = new java.util.HashSet<String>();
            for (var call : calls) ids.add(call.get());
            assertEquals(1, ids.size());
            assertEquals(1, db.queryForObject("SELECT count(*) FROM analysis_jobs WHERE document_id=?", Integer.class, document));
        } finally { executor.shutdownNow(); }
    }

    private String request(String id, String text) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        return json.writeValueAsString(Map.of("documentId", id, "contentHash", hash, "analysisVersion", "v1", "text", text));
    }
}
