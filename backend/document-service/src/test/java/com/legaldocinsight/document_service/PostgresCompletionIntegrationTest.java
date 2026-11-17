package com.legaldocinsight.document_service;

import com.legaldocinsight.document_service.entity.Document;
import com.legaldocinsight.document_service.entity.Document.DocumentStatus;
import com.legaldocinsight.document_service.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in: the supplied PostgreSQL database is dedicated to this test and its schema is recreated. */
@EnabledIfSystemProperty(named = "test.postgres.url", matches = "jdbc:postgresql:.*/legaldoc_feature2_test(?:\\?.*)?")
@DataJpaTest(properties = {
    "spring.datasource.url=${test.postgres.url}",
    "spring.datasource.username=${test.postgres.username:postgres}",
    "spring.datasource.password=${test.postgres.password:}",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgresCompletionIntegrationTest {
    @Autowired DocumentRepository documents;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void clearDedicatedDatabase() {
        documents.deleteAll();
    }

    @Test
    void concurrentWorkersCanClaimAJobOnlyOnce() throws Exception {
        String id = newDocument(DocumentStatus.PENDING);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> { start.await(); return claim(id); });
            var second = workers.submit(() -> { start.await(); return claim(id); });
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            assertEquals(DocumentStatus.PROCESSING, documents.findById(id).orElseThrow().getStatus());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void completionAfterCommittedDeletionCannotRecreateTheDocument() throws Exception {
        String id = newDocument(DocumentStatus.PROCESSING);
        var deletionFlushed = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var completionStarted = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var deleting = workers.submit(() -> transactions.execute(status -> {
                documents.deleteById(id);
                documents.flush();
                deletionFlushed.countDown();
                try {
                    if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return null;
            }));
            assertTrue(deletionFlushed.await(10, TimeUnit.SECONDS));
            var completing = workers.submit(() -> {
                completionStarted.countDown();
                return transactions.execute(status -> finish(id));
            });
            assertTrue(completionStarted.await(10, TimeUnit.SECONDS));
            allowCommit.countDown();
            deleting.get(10, TimeUnit.SECONDS);
            assertEquals(0, completing.get(10, TimeUnit.SECONDS));
            assertFalse(documents.existsById(id));
        } finally {
            allowCommit.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void resetRejectsOldCompletionAndTerminalResultIsDurable() {
        String id = newDocument(DocumentStatus.PROCESSING);
        assertEquals(1, transactions.execute(status -> documents.resetInterrupted(DocumentStatus.PENDING, DocumentStatus.PROCESSING)).intValue());
        assertEquals(0, transactions.execute(status -> finish(id)).intValue());
        assertEquals(DocumentStatus.PENDING, documents.findById(id).orElseThrow().getStatus());
        assertEquals(1, claim(id));
        assertEquals(1, transactions.execute(status -> finish(id)).intValue());
        var saved = documents.findById(id).orElseThrow();
        assertEquals(DocumentStatus.EXTRACTED, saved.getStatus());
        assertEquals("synthetic text", saved.getExtractedText());
    }

    private String newDocument(DocumentStatus status) {
        return documents.saveAndFlush(Document.builder()
            .originalFileName("synthetic.pdf").storedFileName("synthetic.pdf")
            .filePath("synthetic-path").fileSize(1L).fileType("application/pdf")
            .status(status).build()).getId();
    }

    private int claim(String id) {
        return transactions.execute(status -> documents.claimPending(id, DocumentStatus.PENDING, DocumentStatus.PROCESSING));
    }

    private int finish(String id) {
        return documents.finishIfProcessing(id, DocumentStatus.PROCESSING,
            DocumentStatus.EXTRACTED, "synthetic text", 1, 14, null, null);
    }
}
