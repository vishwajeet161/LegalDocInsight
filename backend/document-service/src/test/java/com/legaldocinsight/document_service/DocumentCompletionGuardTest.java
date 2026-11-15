package com.legaldocinsight.document_service;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class DocumentCompletionGuardTest {
    @Autowired DocumentRepository documents;
    @Autowired EntityManager entityManager;

    @Test
    void staleWorkerCannotCompleteAResetOrDeletedDocument() {
        var document = documents.saveAndFlush(Document.builder()
            .originalFileName("synthetic.pdf").storedFileName("synthetic.pdf")
            .filePath("synthetic-path").fileSize(1L).fileType("application/pdf")
            .status(DocumentStatus.PENDING).build());
        assertEquals(0, documents.finishIfProcessing(document.getId(), DocumentStatus.PROCESSING,
            DocumentStatus.EXTRACTED, "synthetic text", 1, 14, null, null));
        entityManager.clear();
        var current = documents.findById(document.getId()).orElseThrow();
        assertEquals(DocumentStatus.PENDING, current.getStatus());
        current.setStatus(DocumentStatus.PROCESSING);
        documents.saveAndFlush(current);
        assertEquals(1, documents.finishIfProcessing(document.getId(), DocumentStatus.PROCESSING,
            DocumentStatus.EXTRACTED, "synthetic text", 1, 14, null, null));
        assertEquals(DocumentStatus.EXTRACTED, documents.findById(document.getId()).orElseThrow().getStatus());
        documents.deleteById(document.getId());
        documents.flush();
        assertEquals(0, documents.finishIfProcessing(document.getId(), DocumentStatus.PROCESSING,
            DocumentStatus.EXTRACTED, "synthetic text", 1, 14, null, null));
        assertEquals(false, documents.existsById(document.getId()));
    }
}
