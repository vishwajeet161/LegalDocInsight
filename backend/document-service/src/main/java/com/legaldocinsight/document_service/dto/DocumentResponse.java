package com.legaldocinsight.document_service.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class DocumentResponse {
    private String id;
    private String originalFileName;
    private Long fileSize;
    private String fileType;
    private String status;
    private Integer pageCount;
    private Integer characterCount;
    private String extractedText;
    private String uploadedBy;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
