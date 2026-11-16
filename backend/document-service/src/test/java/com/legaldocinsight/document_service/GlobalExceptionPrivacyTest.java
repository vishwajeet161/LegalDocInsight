package com.legaldocinsight.document_service;

import com.legaldocinsight.document_service.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionPrivacyTest {
    @Test
    void unhandledExceptionDoesNotRevealItsMessageOrStackTrace() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            var response = new GlobalExceptionHandler().handleGeneral(new IllegalStateException("synthetic-private-detail"));
            assertFalse(response.getBody().toString().contains("synthetic-private-detail"));
            assertTrue(captured.list.stream().noneMatch(event -> event.getFormattedMessage().contains("synthetic-private-detail")));
            captured.list.forEach(event -> assertNull(event.getThrowableProxy()));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
