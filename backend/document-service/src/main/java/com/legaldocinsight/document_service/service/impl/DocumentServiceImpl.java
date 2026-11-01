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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.apache.tika.Tika;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final ExtractionOrchestrator extractionOrchestrator;  // separate bean for @Async
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    @Value("${document.access-token-ttl:PT168H}")
    private Duration accessTokenTtl = Duration.ofDays(7);

    private static final List<String> ALLOWED_TYPES = Arrays.asList(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    @Value("${document.upload.max-bytes:10485760}")
    private long maxFileSize = 10 * 1024 * 1024L;

    @Override
    @Transactional
    public DocumentUploadResponse uploadDocument(MultipartFile file, String userId) {
        validateFile(file);
        log.info("Upload started: size={}", file.getSize());

        byte[] tokenBytes = new byte[32];
        TOKEN_RANDOM.nextBytes(tokenBytes);
        String accessToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String tokenHash = HexFormat.of().formatHex(sha256(tokenBytes));
        Instant expiresAt = Instant.now().plus(accessTokenTtl);

        // 1. Save record immediately with PENDING status
        Document document = Document.builder()
            .originalFileName(file.getOriginalFilename())
            .storedFileName("")
            .filePath("")
            .fileSize(file.getSize())
            .fileType(file.getContentType())
            .status(DocumentStatus.PENDING)
            .accessTokenHash(tokenHash)
            .accessTokenExpiresAt(expiresAt)
            .build();

        document = documentRepository.save(document);
        final String documentId = document.getId();
        log.info("Document record created: documentId={}", documentId);

        // 2. Store file to disk
        try {
            String filePath = storageService.store(file, documentId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        try {
                            storageService.delete(filePath);
                        } catch (Exception ex) {
                            log.error("Failed to remove file after rollback: documentId={}", documentId, ex);
                        }
                    }
                }
            });
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
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    extractionOrchestrator.extractAsync(documentId);
                }
            }
        );

        return DocumentUploadResponse.builder()
            .documentId(documentId)
            .accessToken(accessToken)
            .expiresAt(expiresAt)
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
            log.error("Could not delete file from storage: documentId={}", documentId);
            throw new IllegalStateException("Failed to delete document file", e);
        }
        documentRepository.delete(document);
        log.info("Document deleted: documentId={}", documentId);
    }

    // ─── Helpers ────────────────────────────────────────────────

    private Document findAndAuthorize(String documentId, String userId) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (!tokenMatches(document, userId)) {
            log.warn("Document access denied: documentId={}", documentId);
            throw new DocumentAccessDeniedException(documentId);
        }
        return document;
    }

    private boolean tokenMatches(Document document, String suppliedToken) {
        if (suppliedToken == null || document.getAccessTokenHash() == null
                || document.getAccessTokenExpiresAt() == null
                || !document.getAccessTokenExpiresAt().isAfter(Instant.now())) {
            return false;
        }
        try {
            byte[] suppliedBytes = Base64.getUrlDecoder().decode(suppliedToken);
            if (suppliedBytes.length != 32) return false;
            byte[] expectedHash = HexFormat.of().parseHex(document.getAccessTokenHash());
            return MessageDigest.isEqual(expectedHash, sha256(suppliedBytes));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("File is empty or missing");
        }
        if (file.getSize() > maxFileSize) {
            throw new InvalidFileException("File exceeds the configured size limit");
        }
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new InvalidFileException(
                "Unsupported file type: " + file.getContentType() +
                ". Allowed: PDF, DOCX"
            );
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
            throw new InvalidFileException("Invalid file name");
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        boolean pdf = lowerName.endsWith(".pdf") && "application/pdf".equals(file.getContentType());
        boolean docx = lowerName.endsWith(".docx")
            && ALLOWED_TYPES.get(1).equals(file.getContentType());
        if (!pdf && !docx) throw new InvalidFileException("File extension and type do not match");
        try (InputStream content = file.getInputStream()) {
            String detected = new Tika().detect(content, name);
            if (!file.getContentType().equals(detected)) {
                throw new InvalidFileException("File contents do not match the claimed type");
            }
        } catch (IOException ex) {
            throw new InvalidFileException("Unable to read file contents");
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
