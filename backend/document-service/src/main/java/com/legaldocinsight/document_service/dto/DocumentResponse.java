package com.legaldocinsight.document_service.dto;

import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class DocumentResponse {
    private String id;
    private String originalFileName;
    private Long fileSize;
    private String fileType;
    private DocumentStatus status;
    private Integer pageCount;
    private Integer characterCount;
    private String extractedText;
    private String uploadedBy;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
