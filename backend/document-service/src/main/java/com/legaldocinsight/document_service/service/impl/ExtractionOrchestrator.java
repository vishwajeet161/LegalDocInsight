package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.StorageService;
import com.legaldocinsight.document_service.messaging.DocumentEventPublisher;
import com.legaldocinsight.document_service.messaging.event.DocumentExtractedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import java.time.LocalDateTime;

import java.io.InputStream;
import java.io.ByteArrayInputStream;

/**
 * Separate bean for async extraction.
 * Required because Spring @Async only works when called from a DIFFERENT bean.
 * If DocumentServiceImpl called its own @Async method, the proxy is bypassed
 * and it runs synchronously — defeating the purpose entirely.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionOrchestrator {

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final TikaExtractionService tikaExtractionService;
    private final ClamAvScanner scanner;
    private final ObjectProvider<DocumentEventPublisher> eventPublisher;
    private final TransactionTemplate transactions;
    // private final DocumentExtractedEvent documentExtractedEvent;

    @Async("extractionExecutor")
    public void extractAsync(String documentId) {
        log.info("Async extraction started: documentId={}", documentId);

        Document document = transactions.execute(status -> {
            if (documentRepository.claimPending(documentId, DocumentStatus.PENDING, DocumentStatus.PROCESSING) != 1) {
                return null;
            }
            return documentRepository.findById(documentId).orElse(null);
        });
        if (document == null) return;

        TikaExtractionService.ExtractionResult result = null;
        String failureCode = null;
        try (InputStream inputStream = storageService.retrieve(document.getFilePath())) {
            // The parser consumes precisely the bytes that the scanner examined.
            // Bound memory even if the stored file changes after upload.
            byte[] bytes = inputStream.readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length > 10 * 1024 * 1024) {
                failureCode = "INPUT_LIMIT_EXCEEDED";
            } else {
                ClamAvScanner.Result scan = scanner.scan(new ByteArrayInputStream(bytes));
                if (scan == ClamAvScanner.Result.CLEAN) {
                    result = tikaExtractionService.extract(new ByteArrayInputStream(bytes), document.getOriginalFileName());
                    if (result == null || !result.isSuccess()) {
                        failureCode = result != null && result.getErrorCode() != null
                            ? result.getErrorCode() : "EXTRACTION_FAILED";
                    }
                } else if (scan == ClamAvScanner.Result.INFECTED) {
                    failureCode = "MALWARE_DETECTED";
                } else if (scan == ClamAvScanner.Result.SCAN_LIMIT_EXCEEDED) {
                    failureCode = "UNSAFE_ARCHIVE";
                } else if (scan == ClamAvScanner.Result.TIMEOUT) {
                    failureCode = "SCANNER_TIMEOUT";
                } else {
                    failureCode = "SCANNER_UNAVAILABLE";
                }
            }
        } catch (Exception e) {
            log.error("Extraction failed: documentId={}", documentId);
            failureCode = "INTERNAL_ERROR";
        }

        TikaExtractionService.ExtractionResult extraction = result;
        String safeFailureCode = failureCode;
        Document completed = transactions.execute(status -> {
            Document current = documentRepository.findById(documentId).orElse(null);
            if (current == null || current.getStatus() != DocumentStatus.PROCESSING) return null;
            if (safeFailureCode == null && extraction != null && extraction.isSuccess()) {
                current.setExtractedText(extraction.getText());
                current.setCharacterCount(extraction.getCharacterCount());
                current.setPageCount(extraction.getPageCount());
                current.setStatus(DocumentStatus.EXTRACTED);
                current.setErrorMessage(null);
                current.setErrorCode(null);
            } else {
                current.setStatus(DocumentStatus.FAILED);
                current.setErrorMessage("Text extraction failed.");
                current.setErrorCode(safeFailureCode != null ? safeFailureCode : "INTERNAL_ERROR");
            }
            return documentRepository.save(current);
        });
        if (completed != null && completed.getStatus() == DocumentStatus.EXTRACTED) {
            extractAndPublish(completed);
        }
    }


    public void extractAndPublish(Document document) {

        // 1. Publish event — analysis service picks this up
        DocumentExtractedEvent event = new DocumentExtractedEvent(
            document.getId(),
            document.getUploadedBy(),
            document.getOriginalFileName(),
            document.getFilePath(),
            document.getFileType(),
            document.getExtractedText(),   // already saved to entity by Tika service
            document.getPageCount(),
            document.getCharacterCount(),
            LocalDateTime.now()
        );
        DocumentEventPublisher publisher = eventPublisher.getIfAvailable();
        if (publisher != null) publisher.publishDocumentExtracted(event);
    }
}
