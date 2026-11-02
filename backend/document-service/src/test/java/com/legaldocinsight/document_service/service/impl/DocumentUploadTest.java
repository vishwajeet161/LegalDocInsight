package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.exception.InvalidFileException;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.core.task.TaskRejectedException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class DocumentUploadTest {
    @Test
    void fullExecutorDoesNotTurnAnAcceptedUploadIntoAnErrorAfterCommit() throws Exception {
        var repository = mock(DocumentRepository.class);
        var storage = mock(StorageService.class);
        var extraction = mock(ExtractionOrchestrator.class);
        var service = new DocumentServiceImpl(repository, storage, extraction);
        when(repository.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId("document-1");
            return document;
        });
        when(storage.store(any(), anyString())).thenReturn("stored/document-1.pdf");
        doThrow(new TaskRejectedException("full")).when(extraction).extractAsync("document-1");
        var file = new MockMultipartFile("file", "contract.pdf", "application/pdf",
            "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.uploadDocument(file, null);
            assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void uploadReturnsTokenAndStoresOnlyItsVerifier() throws Exception {
        var repository = mock(DocumentRepository.class);
        var storage = mock(StorageService.class);
        var service = new DocumentServiceImpl(repository, storage, mock(ExtractionOrchestrator.class));
        when(repository.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId("document-1");
            when(repository.findById("document-1")).thenReturn(Optional.of(document));
            return document;
        });
        when(storage.store(any(), anyString())).thenReturn("stored/document-1.pdf");
        var file = new MockMultipartFile("file", "contract.pdf", "application/pdf",
            "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));

        TransactionSynchronizationManager.initSynchronization();
        try {
            var response = service.uploadDocument(file, null);
            assertNotNull(response.getAccessToken());
            assertNotNull(response.getExpiresAt());
            var stored = repository.findById("document-1").orElseThrow();
            assertNotEquals(response.getAccessToken(), stored.getAccessTokenHash());
            assertDoesNotThrow(() -> service.getDocument("document-1", response.getAccessToken()));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rejectsContentThatDoesNotMatchTheClaimedPdfType() {
        var service = new DocumentServiceImpl(mock(DocumentRepository.class), mock(StorageService.class),
            mock(ExtractionOrchestrator.class));
        var file = new MockMultipartFile("file", "contract.pdf", "application/pdf",
            "not a pdf".getBytes(StandardCharsets.US_ASCII));

        assertThrows(InvalidFileException.class, () -> service.uploadDocument(file, null));
    }

    @Test
    void rejectsMissingEmptyAndOversizedFiles() {
        var service = new DocumentServiceImpl(mock(DocumentRepository.class), mock(StorageService.class),
            mock(ExtractionOrchestrator.class));
        var empty = new MockMultipartFile("file", "contract.pdf", "application/pdf", new byte[0]);
        var oversized = new MockMultipartFile("file", "contract.pdf", "application/pdf", new byte[10 * 1024 * 1024 + 1]);

        assertThrows(InvalidFileException.class, () -> service.uploadDocument(null, null));
        assertThrows(InvalidFileException.class, () -> service.uploadDocument(empty, null));
        assertThrows(InvalidFileException.class, () -> service.uploadDocument(oversized, null));
    }

    @Test
    void removesStoredFileWhenDatabaseTransactionRollsBack() throws Exception {
        var repository = mock(DocumentRepository.class);
        var storage = mock(StorageService.class);
        var service = new DocumentServiceImpl(repository, storage, mock(ExtractionOrchestrator.class));
        when(repository.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId("document-1");
            return document;
        });
        when(storage.store(any(), anyString())).thenReturn("stored/document-1.pdf");
        var file = new MockMultipartFile("file", "contract.pdf", "application/pdf",
            "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.uploadDocument(file, null);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(storage).delete("stored/document-1.pdf");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
