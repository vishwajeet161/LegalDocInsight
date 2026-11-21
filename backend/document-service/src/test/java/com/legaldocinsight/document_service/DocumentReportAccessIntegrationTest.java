package com.legaldocinsight.document_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legaldocinsight.document_service.client.AnalysisServiceClient;
import com.legaldocinsight.document_service.service.impl.ExtractionOrchestrator;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:reporttest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "storage.local.upload-dir=${java.io.tmpdir}/legaldoc-report-test"})
@AutoConfigureMockMvc
class DocumentReportAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DocumentRepository documents;
    @MockitoBean ExtractionOrchestrator extraction;
    @MockitoBean AnalysisServiceClient analysis;

    @Test void deletionRequiresAnalysisCleanupBeforeRemovingDocument() throws Exception {
        var file = new MockMultipartFile("file", "synthetic.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4\n%%EOF".getBytes());
        var upload = json.readTree(mvc.perform(multipart("/api/documents/upload").file(file)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        var id = upload.get("documentId").asText();
        var token = upload.get("accessToken").asText();
        var document = documents.findById(id).orElseThrow();
        document.setAnalysisDeliveryStatus("PENDING");
        documents.saveAndFlush(document);
        mvc.perform(delete("/api/documents/{id}", id).header("X-Document-Token", "wrong")).andExpect(status().isForbidden());
        verifyNoInteractions(analysis);
        when(analysis.delete(id)).thenReturn(ResponseEntity.status(503).build(), ResponseEntity.noContent().build());
        mvc.perform(delete("/api/documents/{id}", id).header("X-Document-Token", token)).andExpect(status().isServiceUnavailable());
        org.junit.jupiter.api.Assertions.assertTrue(documents.existsById(id));
        mvc.perform(delete("/api/documents/{id}", id).header("X-Document-Token", token)).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertFalse(documents.existsById(id));
    }

    @Test void terminalHandoffFailureIsVisibleToTokenHolder() throws Exception {
        var file = new MockMultipartFile("file", "synthetic.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4\n%%EOF".getBytes());
        var upload = json.readTree(mvc.perform(multipart("/api/documents/upload").file(file)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String id = upload.get("documentId").asText(), token = upload.get("accessToken").asText();
        var document = documents.findById(id).orElseThrow();
        document.setStatus(com.legaldocinsight.document_service.entity.Document.DocumentStatus.EXTRACTED);
        document.setAnalysisDeliveryStatus("FAILED");
        documents.saveAndFlush(document);
        mvc.perform(get("/api/documents/{id}", id).header("X-Document-Token", token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.errorCode").value("ANALYSIS_HANDOFF_FAILED"));
    }

    @Test void tokenIsCheckedBeforeFetchingReport() throws Exception {
        var file = new MockMultipartFile("file", "synthetic.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4\n%%EOF".getBytes());
        var upload = json.readTree(mvc.perform(multipart("/api/documents/upload").file(file)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        var id = upload.get("documentId").asText();
        var token = upload.get("accessToken").asText();
        mvc.perform(get("/api/documents/{id}/report", id).header("X-User-Id", "forged")).andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/{id}/report", id).header("X-Document-Token", "wrong")).andExpect(status().isForbidden());
        verifyNoInteractions(analysis);
        when(analysis.report(anyString())).thenReturn(ResponseEntity.accepted().build());
        mvc.perform(get("/api/documents/{id}/report", id).header("X-Document-Token", token))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"));
        when(analysis.report(anyString())).thenReturn(ResponseEntity.notFound().build());
        mvc.perform(get("/api/documents/{id}/report", id).header("X-Document-Token", token)).andExpect(status().isNotFound());
        when(analysis.report(anyString())).thenReturn(ResponseEntity.status(503).build());
        mvc.perform(get("/api/documents/{id}/report", id).header("X-Document-Token", token)).andExpect(status().isServiceUnavailable());
        var document = documents.findById(id).orElseThrow();
        document.setAccessTokenExpiresAt(java.time.Instant.now().minusSeconds(1));
        documents.saveAndFlush(document);
        clearInvocations(analysis);
        mvc.perform(get("/api/documents/{id}/report", id).header("X-Document-Token", token)).andExpect(status().isForbidden());
        verifyNoInteractions(analysis);
    }
}
