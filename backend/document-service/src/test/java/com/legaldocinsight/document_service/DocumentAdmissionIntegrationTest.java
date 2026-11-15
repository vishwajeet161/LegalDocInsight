package com.legaldocinsight.document_service;

import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.impl.ExtractionOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=${test.datasource.url:jdbc:h2:mem:admissiontest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.driver-class-name=${test.datasource.driver:org.h2.Driver}",
    "spring.datasource.username=${test.datasource.username:sa}",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "storage.local.upload-dir=${java.io.tmpdir}/legaldocinsight-admission-test",
    "document.extraction.max-admitted=1",
    "document.extraction.recovery-delay-ms=3600000"
})
@AutoConfigureMockMvc
class DocumentAdmissionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DocumentRepository documents;
    @MockitoBean ExtractionOrchestrator extraction;

    @Test
    void capacityRejectsNewUploadsUntilAnExistingJobFinishes() throws Exception {
        documents.deleteAll();
        mvc.perform(multipart("/api/documents/upload").file(file())).andExpect(status().isAccepted());
        mvc.perform(multipart("/api/documents/upload").file(file()))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "5"));
        assertEquals(1, documents.count());
        var finished = documents.findAll().get(0);
        finished.setStatus(DocumentStatus.EXTRACTED);
        documents.saveAndFlush(finished);
        mvc.perform(multipart("/api/documents/upload").file(file())).andExpect(status().isAccepted());
    }

    @Test
    void concurrentUploadsCannotExceedTheAdmissionLimit() throws Exception {
        documents.deleteAll();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return mvc.perform(multipart("/api/documents/upload").file(file())).andReturn().getResponse().getStatus(); });
            var second = executor.submit(() -> { start.await(); return mvc.perform(multipart("/api/documents/upload").file(file())).andReturn().getResponse().getStatus(); });
            start.countDown();
            int a = first.get(10, TimeUnit.SECONDS);
            int b = second.get(10, TimeUnit.SECONDS);
            assertTrue((a == 202 && b == 503) || (a == 503 && b == 202));
            assertEquals(1, documents.count());
        } finally {
            executor.shutdownNow();
        }
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "synthetic.pdf", MediaType.APPLICATION_PDF_VALUE,
            "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));
    }
}
