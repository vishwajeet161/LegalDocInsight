package com.legaldocinsight.document_service.repository;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByUploadedByOrderByCreatedAtDesc(String uploadedBy);
    List<Document> findByStatus(DocumentStatus status);
}
