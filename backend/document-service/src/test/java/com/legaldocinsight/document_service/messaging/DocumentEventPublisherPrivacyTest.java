package com.legaldocinsight.document_service.messaging;

import com.legaldocinsight.document_service.messaging.event.DocumentExtractedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentEventPublisherPrivacyTest {
    @Test
    @SuppressWarnings("unchecked")
    void publisherIsAbsentByDefaultAndAvailableOnlyWhenExplicitlyEnabled() {
        KafkaTemplate<String, DocumentExtractedEvent> template = mock(KafkaTemplate.class);
        var context = new ApplicationContextRunner()
            .withUserConfiguration(DocumentEventPublisher.class)
            .withBean(KafkaTemplate.class, () -> template)
            .withPropertyValues("kafka.topics.document-extracted=synthetic-topic");
        context.run(result -> assertTrue(result.getBeansOfType(DocumentEventPublisher.class).isEmpty()));
        context.withPropertyValues("app.kafka.enabled=true")
            .run(result -> assertTrue(result.getBeansOfType(DocumentEventPublisher.class).size() == 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedPublishDoesNotLogExceptionDetails() {
        KafkaTemplate<String, DocumentExtractedEvent> template = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, DocumentExtractedEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("synthetic-private-content"));
        when(template.send(any(Message.class))).thenReturn(failed);
        var publisher = new DocumentEventPublisher(template);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "topic", "test-topic");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DocumentEventPublisher.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            publisher.publishDocumentExtracted(new DocumentExtractedEvent("synthetic-id", null, null,
                null, null, null, null, null, null));
            assertFalse(captured.list.isEmpty());
            assertTrue(captured.list.stream().noneMatch(event -> event.getFormattedMessage().contains("synthetic-private-content")));
            captured.list.forEach(event -> assertNull(event.getThrowableProxy()));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
