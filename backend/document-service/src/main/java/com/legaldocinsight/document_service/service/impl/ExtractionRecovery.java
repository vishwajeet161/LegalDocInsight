package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ExtractionRecovery {
    private final DocumentRepository documents;
    private final ExtractionOrchestrator orchestrator;
    private final TransactionTemplate transactions;
    private volatile boolean ready;

    // The supported deployment has one service instance; the previous instance
    // and its child processes must have stopped before startup recovery runs.
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedWork() {
        transactions.executeWithoutResult(status ->
            documents.resetInterrupted(DocumentStatus.PENDING, DocumentStatus.PROCESSING));
        ready = true;
        dispatchPending();
    }

    @Scheduled(fixedDelayString = "${document.extraction.recovery-delay-ms:5000}")
    public void dispatchPending() {
        if (!ready) return;
        var pending = documents.findByStatusOrderByCreatedAtAsc(DocumentStatus.PENDING, PageRequest.of(0, 50));
        for (var document : pending) {
            try {
                orchestrator.extractAsync(document.getId());
            } catch (TaskRejectedException full) {
                return; // The persisted row is retried on the next poll.
            }
        }
    }
}
