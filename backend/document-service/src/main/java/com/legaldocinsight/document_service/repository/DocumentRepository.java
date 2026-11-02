package com.legaldocinsight.document_service.repository;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByUploadedByOrderByCreatedAtDesc(String uploadedBy);
    List<Document> findByStatus(DocumentStatus status);
    List<Document> findByStatusOrderByCreatedAtAsc(DocumentStatus status, Pageable pageable);

    @Modifying
    @Query("update Document d set d.status = :processing where d.id = :id and d.status = :pending")
    int claimPending(@Param("id") String id, @Param("pending") DocumentStatus pending,
                     @Param("processing") DocumentStatus processing);

    @Modifying
    @Query("update Document d set d.status = :pending where d.status = :processing")
    int resetInterrupted(@Param("pending") DocumentStatus pending,
                         @Param("processing") DocumentStatus processing);
}
