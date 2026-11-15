package com.legaldocinsight.document_service;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.impl.ClamAvScanner;
import com.legaldocinsight.document_service.service.impl.ExtractionOrchestrator;
import com.legaldocinsight.document_service.service.impl.ParserProcess;
import com.legaldocinsight.document_service.service.impl.TikaExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:parserworker;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "storage.local.upload-dir=${java.io.tmpdir}/legaldocinsight-parserworker-test"
})
class ParserWorkerIntegrationTest {
    @Autowired DocumentRepository documents;
    @Autowired ExtractionOrchestrator worker;
    @MockitoBean ClamAvScanner scanner;
    @MockitoBean ParserProcess parser;

    @Test
    void cleanFileUsesChildParserResultForPersistedText() throws Exception {
        var file = Files.createTempFile("synthetic-parser-worker-", ".pdf");
        Files.writeString(file, "synthetic input");
        var document = documents.saveAndFlush(Document.builder()
            .originalFileName("synthetic.pdf").storedFileName("synthetic.pdf")
            .filePath(file.toString()).fileSize(Files.size(file)).fileType("application/pdf")
            .status(DocumentStatus.PENDING).build());
        when(scanner.scan(any())).thenReturn(ClamAvScanner.Result.CLEAN);
        when(parser.extract(any())).thenReturn(TikaExtractionService.ExtractionResult.builder()
            .success(true).text("from child").characterCount(10).build());
        try {
            worker.extractAsync(document.getId());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var saved = documents.findById(document.getId()).orElseThrow();
                assertEquals(DocumentStatus.EXTRACTED, saved.getStatus());
                assertEquals("from child", saved.getExtractedText());
            });
        } finally {
            documents.deleteById(document.getId());
            Files.deleteIfExists(file);
        }
    }
}
