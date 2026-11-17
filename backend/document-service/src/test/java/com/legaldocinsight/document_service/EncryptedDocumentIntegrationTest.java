package com.legaldocinsight.document_service;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.messaging.DocumentEventPublisher;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.impl.ClamAvScanner;
import com.legaldocinsight.document_service.service.impl.ExtractionOrchestrator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:encryptedworker;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "storage.local.upload-dir=${java.io.tmpdir}/legaldocinsight-encrypted-test"
})
class EncryptedDocumentIntegrationTest {
    @Autowired DocumentRepository documents;
    @Autowired ExtractionOrchestrator worker;
    @MockitoBean ClamAvScanner scanner;
    @MockitoBean DocumentEventPublisher publisher;
    @TempDir Path temp;

    @Test
    void encryptedPdfFailsThroughRealParserWithoutPublishingSuccess() throws Exception {
        var file = temp.resolve("synthetic.pdf");
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.protect(new StandardProtectionPolicy("synthetic-owner", "synthetic-user", new AccessPermission()));
            pdf.save(file.toFile());
        }
        when(scanner.scan(any())).thenReturn(ClamAvScanner.Result.CLEAN);
        var document = documents.saveAndFlush(Document.builder()
            .originalFileName("synthetic.pdf").storedFileName("synthetic.pdf")
            .filePath(file.toString()).fileSize(Files.size(file)).fileType("application/pdf")
            .status(DocumentStatus.PENDING).build());
        try {
            worker.extractAsync(document.getId());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var saved = documents.findById(document.getId()).orElseThrow();
                assertEquals(DocumentStatus.FAILED, saved.getStatus());
                assertEquals("ENCRYPTED_DOCUMENT", saved.getErrorCode());
                assertNull(saved.getExtractedText());
            });
            verify(publisher, never()).publishDocumentExtracted(any());
        } finally {
            documents.deleteById(document.getId());
        }
    }
}
