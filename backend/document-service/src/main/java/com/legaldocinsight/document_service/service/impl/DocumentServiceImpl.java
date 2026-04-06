package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.dto.DocumentResponse;
import com.legaldocinsight.document_service.dto.DocumentUploadResponse;
import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.exception.DocumentAccessDeniedException;
import com.legaldocinsight.document_service.exception.DocumentNotFoundException;
import com.legaldocinsight.document_service.exception.InvalidFileException;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import com.legaldocinsight.document_service.service.DocumentService;
import com.legaldocinsight.document_service.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final ExtractionOrchestrator extractionOrchestrator;  // separate bean for @Async

    private static final List<String> ALLOWED_TYPES = Arrays.asList(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword",
        "text/plain"
    );

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024L; // 50MB

    @Override
    @Transactional
    public DocumentUploadResponse uploadDocument(MultipartFile file, String userId) {
        log.info("Upload started: file={} size={} userId={}",
            file.getOriginalFilename(), file.getSize(), userId);

        validateFile(file);

        // 1. Save record immediately with PENDING status
        Document document = Document.builder()
            .originalFileName(file.getOriginalFilename())
            .storedFileName("")
            .filePath("")
            .fileSize(file.getSize())
            .fileType(file.getContentType())
            .status(DocumentStatus.PENDING)
            .uploadedBy(userId)
            .build();

        document = documentRepository.save(document);
        final String documentId = document.getId();
        log.info("Document record created: documentId={}", documentId);

        // 2. Store file to disk
        try {
            String filePath = storageService.store(file, documentId);
            document.setFilePath(filePath);
            document.setStoredFileName(documentId);
            documentRepository.save(document);
            log.debug("File stored: documentId={} path={}", documentId, filePath);
        } catch (Exception e) {
            log.error("Storage failed: documentId={} error={}", documentId, e.getMessage());
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage("File storage failed: " + e.getMessage());
            documentRepository.save(document);
            throw new RuntimeException("Failed to store file", e);
        }

        // 3. Trigger async extraction — returns immediately, runs in background
        extractionOrchestrator.extractAsync(documentId);

        return DocumentUploadResponse.builder()
            .documentId(documentId)
            .fileName(file.getOriginalFilename())
            .fileSize(file.getSize())
            .status(DocumentStatus.PENDING)
            .message("Document uploaded. Text extraction is in progress.")
            .uploadedAt(LocalDateTime.now())
            .build();
    }

    @Override
    public DocumentResponse getDocument(String documentId, String userId) {
        Document document = findAndAuthorize(documentId, userId);
        return mapToResponse(document, false);
    }

    @Override
    public DocumentResponse getDocumentWithText(String documentId, String userId) {
        Document document = findAndAuthorize(documentId, userId);
        return mapToResponse(document, true);
    }

    @Override
    public List<DocumentResponse> getUserDocuments(String userId) {
        return documentRepository.findByUploadedByOrderByCreatedAtDesc(userId)
            .stream()
            .map(doc -> mapToResponse(doc, false))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteDocument(String documentId, String userId) {
        Document document = findAndAuthorize(documentId, userId);
        try {
            storageService.delete(document.getFilePath());
        } catch (Exception e) {
            log.warn("Could not delete file from storage: documentId={}", documentId);
        }
        documentRepository.delete(document);
        log.info("Document deleted: documentId={} userId={}", documentId, userId);
    }

    // ─── Helpers ────────────────────────────────────────────────

    private Document findAndAuthorize(String documentId, String userId) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (!document.getUploadedBy().equals(userId)) {
            log.warn("Unauthorized: documentId={} requestedBy={} ownedBy={}",
                documentId, userId, document.getUploadedBy());
            throw new DocumentAccessDeniedException(documentId);
        }
        return document;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("File is empty or missing");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidFileException("File exceeds maximum size of 50MB");
        }
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new InvalidFileException(
                "Unsupported file type: " + file.getContentType() +
                ". Allowed: PDF, DOCX, DOC, TXT"
            );
        }
    }

    private DocumentResponse mapToResponse(Document doc, boolean includeText) {
        return DocumentResponse.builder()
            .id(doc.getId())
            .originalFileName(doc.getOriginalFileName())
            .fileSize(doc.getFileSize())
            .fileType(doc.getFileType())
            .status(doc.getStatus())
            .pageCount(doc.getPageCount())
            .characterCount(doc.getCharacterCount())
            .extractedText(includeText ? doc.getExtractedText() : null)
            .uploadedBy(doc.getUploadedBy())
            .errorMessage(doc.getErrorMessage())
            .createdAt(doc.getCreatedAt())
            .updatedAt(doc.getUpdatedAt())
            .build();
    }
}
