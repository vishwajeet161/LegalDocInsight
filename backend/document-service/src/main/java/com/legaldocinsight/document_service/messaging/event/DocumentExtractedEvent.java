package com.legaldocinsight.document_service.messaging.event;

import java.time.LocalDateTime;

public record DocumentExtractedEvent(
        String documentId,
        String uploadedBy,
        String originalFileName,
        String filePath,
        String fileType,          // your mimeType equivalent
        String extractedText,
        Integer pageCount,
        Integer characterCount,
        LocalDateTime extractedAt
) {}