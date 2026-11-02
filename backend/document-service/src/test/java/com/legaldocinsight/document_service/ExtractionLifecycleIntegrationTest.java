package com.legaldocinsight.document_service;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.impl.ExtractionOrchestrator;
import com.legaldocinsight.document_service.service.impl.TikaExtractionService;
import com.legaldocinsight.document_service.service.impl.ExtractionRecovery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:extractiontest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "storage.local.upload-dir=${java.io.tmpdir}/legaldocinsight-extraction-test"
})
class ExtractionLifecycleIntegrationTest {
    @Autowired DocumentRepository documents;
    @Autowired ExtractionOrchestrator orchestrator;
    @Autowired ExtractionRecovery recovery;
    @MockitoBean TikaExtractionService extractor;

    @Test
    void duplicateSchedulingDoesNotParseTwiceOrRecreateDeletedDocument() throws Exception {
        var file = Files.createTempFile("synthetic-delete-", ".pdf");
        Files.writeString(file, "synthetic input");
        var document = documents.saveAndFlush(Document.builder()
            .originalFileName("synthetic.pdf").storedFileName("synthetic.pdf")
            .filePath(file.toString()).fileSize(Files.size(file)).fileType("application/pdf")
            .status(DocumentStatus.PENDING).build());
        var started = new CountDownLatch(1);
        var secondParse = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(extractor.extract(any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() > 1) secondParse.countDown();
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return TikaExtractionService.ExtractionResult.builder()
                .success(true).text("synthetic text").characterCount(14).pageCount(1).build();
        });
        try {
            orchestrator.extractAsync(document.getId());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            orchestrator.extractAsync(document.getId());
            assertFalse(secondParse.await(1, TimeUnit.SECONDS));
            documents.deleteById(document.getId());
        } finally {
            release.countDown();
        }
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
            .until(() -> documents.findById(document.getId()).isEmpty());
        assertEquals(1, calls.get());
        Files.deleteIfExists(file);
    }

    @Test
    void interruptedProcessingIsResubmittedOnRecovery() throws Exception {
        var file = Files.createTempFile("synthetic-recovery-", ".pdf");
        Files.writeString(file, "synthetic input");
        var document = documents.saveAndFlush(Document.builder()
            .originalFileName("synthetic.pdf").storedFileName("synthetic.pdf")
            .filePath(file.toString()).fileSize(Files.size(file)).fileType("application/pdf")
            .status(DocumentStatus.PROCESSING).build());
        when(extractor.extract(any(), any())).thenReturn(TikaExtractionService.ExtractionResult.builder()
            .success(true).text("synthetic text").characterCount(14).pageCount(1).build());

        recovery.recoverInterruptedWork();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var recovered = documents.findById(document.getId()).orElseThrow();
            assertEquals(DocumentStatus.EXTRACTED, recovered.getStatus());
            assertEquals("synthetic text", recovered.getExtractedText());
        });
        documents.deleteById(document.getId());
        Files.deleteIfExists(file);
    }

    @Test
    void processingIsCommittedWhileExtractionIsRunning() throws Exception {
        var file = Files.createTempFile("synthetic-contract-", ".pdf");
        Files.writeString(file, "synthetic input");
        var document = documents.saveAndFlush(Document.builder()
            .originalFileName("synthetic.pdf").storedFileName("synthetic.pdf")
            .filePath(file.toString()).fileSize(Files.size(file)).fileType("application/pdf")
            .status(DocumentStatus.PENDING).build());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(extractor.extract(any(), any())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return TikaExtractionService.ExtractionResult.builder()
                .success(true).text("synthetic text").characterCount(14).pageCount(1).build();
        });
        try {
            orchestrator.extractAsync(document.getId());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(DocumentStatus.PROCESSING, documents.findById(document.getId()).orElseThrow().getStatus());
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertEquals(DocumentStatus.EXTRACTED, documents.findById(document.getId()).orElseThrow().getStatus()));
        documents.deleteById(document.getId());
        Files.deleteIfExists(file);
    }
}
