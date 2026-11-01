package com.legaldocinsight.document_service.dto;

import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.time.Instant;

@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class DocumentUploadResponse {
    private String documentId;
    private String accessToken;
    private Instant expiresAt;
    private String fileName;
    private Long fileSize;
    private DocumentStatus status;
    private String message;
    private LocalDateTime uploadedAt;
}
