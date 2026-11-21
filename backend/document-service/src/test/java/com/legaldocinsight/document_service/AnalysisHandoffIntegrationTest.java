package com.legaldocinsight.document_service;

import com.legaldocinsight.document_service.client.AnalysisServiceClient;
import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.impl.AnalysisHandoff;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:handoff;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
    "storage.local.upload-dir=${java.io.tmpdir}/legaldocinsight-handoff-test"
})
class AnalysisHandoffIntegrationTest {
    @Autowired DocumentRepository documents;
    @Autowired AnalysisHandoff handoff;
    @MockitoBean AnalysisServiceClient client;

    @Test void retriesTransientFailureAndDoesNotRedeliverAfterSuccess() {
        var document = documents.saveAndFlush(Document.builder().originalFileName("synthetic.pdf")
            .storedFileName("synthetic.pdf").filePath("synthetic-path").fileSize(1L).fileType("application/pdf")
            .status(Document.DocumentStatus.EXTRACTED).extractedText("synthetic text").analysisDeliveryStatus("PENDING").build());
        try {
            when(client.submit(eq(document.getId()), anyString(), eq("synthetic text")))
                .thenReturn(ResponseEntity.status(503).build(), ResponseEntity.accepted().build());
            handoff.deliver(document.getId());
            assertEquals("PENDING", documents.findById(document.getId()).orElseThrow().getAnalysisDeliveryStatus());
            handoff.deliver(document.getId());
            assertEquals("DELIVERED", documents.findById(document.getId()).orElseThrow().getAnalysisDeliveryStatus());
            handoff.deliver(document.getId());
            verify(client, times(2)).submit(eq(document.getId()), anyString(), eq("synthetic text"));
        } finally { documents.deleteById(document.getId()); }
    }
}
