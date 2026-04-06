package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;

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

    @Async("extractionExecutor")
    @Transactional
    public void extractAsync(String documentId) {
        log.info("Async extraction started: documentId={}", documentId);

        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        // Mark as PROCESSING
        document.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(document);

        try (InputStream inputStream = storageService.retrieve(document.getFilePath())) {

            TikaExtractionService.ExtractionResult result =
                tikaExtractionService.extract(inputStream, document.getOriginalFileName());

            if (result.isSuccess()) {
                document.setExtractedText(result.getText());
                document.setCharacterCount(result.getCharacterCount());
                document.setPageCount(result.getPageCount());
                document.setStatus(DocumentStatus.EXTRACTED);

                log.info("Extraction complete: documentId={} chars={} pages={}",
                    documentId, result.getCharacterCount(), result.getPageCount());
            } else {
                document.setStatus(DocumentStatus.FAILED);
                document.setErrorMessage(result.getErrorMessage());
                log.error("Extraction failed: documentId={} reason={}",
                    documentId, result.getErrorMessage());
            }

        } catch (Exception e) {
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage(e.getMessage());
            log.error("Extraction exception: documentId={}", documentId, e);
        }

        documentRepository.save(document);
        log.info("Document status updated: documentId={} status={}",
            documentId, document.getStatus());
    }
}
