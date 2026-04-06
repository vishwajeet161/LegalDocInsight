package com.legaldocinsight.document_service.service;

import com.legaldocinsight.document_service.dto.DocumentResponse;
import com.legaldocinsight.document_service.dto.DocumentUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {
    DocumentUploadResponse uploadDocument(MultipartFile file, String userId);
    DocumentResponse getDocument(String documentId, String userId);
    DocumentResponse getDocumentWithText(String documentId, String userId);
    List<DocumentResponse> getUserDocuments(String userId);
    void deleteDocument(String documentId, String userId);
}
