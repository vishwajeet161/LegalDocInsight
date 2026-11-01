package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.exception.DocumentAccessDeniedException;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.StorageService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.time.Instant;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DocumentAccessTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TOKEN_HASH = "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925";

    @Test
    void legacyAnonymousOwnerMustNotGrantAccessWithoutDocumentToken() {
        var repository = mock(DocumentRepository.class);
        var storage = mock(StorageService.class);
        var extraction = mock(ExtractionOrchestrator.class);
        var service = new DocumentServiceImpl(repository, storage, extraction);
        var document = Document.builder()
            .id("document-1")
            .uploadedBy("anonymous")
            .build();
        when(repository.findById("document-1")).thenReturn(Optional.of(document));

        assertThrows(DocumentAccessDeniedException.class,
            () -> service.getDocument("document-1", "anonymous"));
    }

    @Test
    void matchingUnexpiredTokenGrantsAccess() {
        var service = serviceWithToken(Instant.now().plusSeconds(300));
        assertDoesNotThrow(() -> service.getDocument("document-1", TOKEN));
    }

    @Test
    void wrongOrMissingTokenCannotGrantAccess() {
        var service = serviceWithToken(Instant.now().plusSeconds(300));
        assertThrows(DocumentAccessDeniedException.class, () -> service.getDocument("document-1", null));
        assertThrows(DocumentAccessDeniedException.class, () -> service.getDocument("document-1", "wrong"));
    }

    @Test
    void expiredTokenCannotGrantAccess() {
        var service = serviceWithToken(Instant.now().minusSeconds(1));
        assertThrows(DocumentAccessDeniedException.class, () -> service.getDocument("document-1", TOKEN));
    }

    @Test
    void failedFileDeletionKeepsTheDocumentRecordForRetry() throws Exception {
        var repository = mock(DocumentRepository.class);
        var storage = mock(StorageService.class);
        var document = Document.builder().id("document-1").filePath("stored/document-1.pdf")
            .accessTokenHash(TOKEN_HASH).accessTokenExpiresAt(Instant.now().plusSeconds(300)).build();
        when(repository.findById("document-1")).thenReturn(Optional.of(document));
        doThrow(new IOException("synthetic storage failure")).when(storage).delete("stored/document-1.pdf");
        var service = new DocumentServiceImpl(repository, storage, mock(ExtractionOrchestrator.class));

        assertThrows(RuntimeException.class, () -> service.deleteDocument("document-1", TOKEN));
        verify(repository, never()).delete(document);
    }

    private DocumentServiceImpl serviceWithToken(Instant expiresAt) {
        var repository = mock(DocumentRepository.class);
        var document = Document.builder()
            .id("document-1")
            .accessTokenHash(TOKEN_HASH)
            .accessTokenExpiresAt(expiresAt)
            .build();
        when(repository.findById("document-1")).thenReturn(Optional.of(document));
        return new DocumentServiceImpl(repository, mock(StorageService.class), mock(ExtractionOrchestrator.class));
    }
}
