package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;

/** Polls durable delivery state; disabled until explicitly configured. */
@Service
public class AnalysisHandoffDispatcher {
    private final DocumentRepository documents;
    private final AnalysisHandoff handoff;
    private final boolean enabled;

    public AnalysisHandoffDispatcher(DocumentRepository documents, AnalysisHandoff handoff,
            @Value("${analysis.handoff.enabled:false}") boolean enabled) {
        this.documents = documents; this.handoff = handoff; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${analysis.handoff.poll-delay-ms:5000}")
    public synchronized void dispatch() {
        if (!enabled) return;
        for (String id : documents.findDeliverable(DocumentStatus.EXTRACTED, Instant.now(), PageRequest.of(0, 20)))
            handoff.deliver(id);
    }
}
