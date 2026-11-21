package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.client.AnalysisServiceClient;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A single delivery attempt. Scheduling and retry pacing are separate. */
@Service
public class AnalysisHandoff {
    private final DocumentRepository documents;
    private final AnalysisServiceClient client;
    public AnalysisHandoff(DocumentRepository documents, AnalysisServiceClient client) {
        this.documents = documents;
        this.client = client;
    }

    @Transactional
    public void deliver(String documentId) {
        var document = documents.findForDelivery(documentId).orElse(null);
        if (document == null || document.getStatus() != DocumentStatus.EXTRACTED
                || !"PENDING".equals(document.getAnalysisDeliveryStatus())) return;
        var response = client.submit(documentId, "v1", document.getExtractedText());
        if (response.getStatusCode().value() == 202) document.setAnalysisDeliveryStatus("DELIVERED");
        else if (response.getStatusCode().value() == 400) document.setAnalysisDeliveryStatus("FAILED");
    }
}
