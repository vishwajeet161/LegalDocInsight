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
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.time.Instant;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByUploadedByOrderByCreatedAtDesc(String uploadedBy);
    List<Document> findByStatus(DocumentStatus status);
    long countByStatusIn(Collection<DocumentStatus> statuses);
    List<Document> findByStatusOrderByCreatedAtAsc(DocumentStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findForDelivery(@Param("id") String id);

    @Query("select d.id from Document d where d.status = :status and d.analysisDeliveryStatus = 'PENDING' "
        + "and (d.analysisNextAttemptAt is null or d.analysisNextAttemptAt <= :now) order by d.createdAt, d.id")
    List<String> findDeliverable(@Param("status") DocumentStatus status, @Param("now") Instant now, Pageable limit);

    @Modifying
    @Query("update Document d set d.status = :processing where d.id = :id and d.status = :pending")
    int claimPending(@Param("id") String id, @Param("pending") DocumentStatus pending,
                     @Param("processing") DocumentStatus processing);

    @Modifying
    @Query("update Document d set d.status = :pending where d.status = :processing")
    int resetInterrupted(@Param("pending") DocumentStatus pending,
                         @Param("processing") DocumentStatus processing);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Document d set d.status = :terminal, d.extractedText = :text, "
        + "d.pageCount = :pages, d.characterCount = :characters, d.analysisDeliveryStatus = :delivery, d.errorCode = :code, "
        + "d.errorMessage = :message, d.updatedAt = CURRENT_TIMESTAMP "
        + "where d.id = :id and d.status = :processing")
    int finishIfProcessing(@Param("id") String id, @Param("processing") DocumentStatus processing,
        @Param("terminal") DocumentStatus terminal, @Param("text") String text,
        @Param("pages") Integer pages, @Param("characters") Integer characters,
        @Param("code") String code, @Param("message") String message, @Param("delivery") String delivery);
}
