package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ExtractionRecoveryTest {
    @Test
    void scheduledDispatchWaitsUntilInterruptedJobsHaveBeenReset() {
        var repository = mock(DocumentRepository.class);
        var orchestrator = mock(ExtractionOrchestrator.class);
        var recovery = new ExtractionRecovery(repository, orchestrator, mock(TransactionTemplate.class));

        recovery.dispatchPending();

        verifyNoInteractions(repository, orchestrator);
    }
}
