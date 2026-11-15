package com.legaldocinsight.document_service.service.impl;

import com.legaldocinsight.document_service.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtractionRecoveryTest {
    @Test
    void scheduledDispatchWaitsUntilInterruptedJobsHaveBeenReset() {
        var repository = mock(DocumentRepository.class);
        var orchestrator = mock(ExtractionOrchestrator.class);
        var recovery = new ExtractionRecovery(repository, orchestrator, mock(TransactionTemplate.class), mock(ParserProcess.class));

        recovery.dispatchPending();

        verifyNoInteractions(repository, orchestrator);
    }

    @Test
    void interruptedJobsAreNotReclaimedWhenStaleParserCleanupFails() {
        var repository = mock(DocumentRepository.class);
        var orchestrator = mock(ExtractionOrchestrator.class);
        var transactions = mock(TransactionTemplate.class);
        var parser = mock(ParserProcess.class);
        doThrow(new IllegalStateException("synthetic cleanup failure")).when(parser).reapStaleChildren();
        var recovery = new ExtractionRecovery(repository, orchestrator, transactions, parser);
        assertThrows(IllegalStateException.class, recovery::recoverInterruptedWork);
        verifyNoInteractions(repository, orchestrator, transactions);
    }
}
