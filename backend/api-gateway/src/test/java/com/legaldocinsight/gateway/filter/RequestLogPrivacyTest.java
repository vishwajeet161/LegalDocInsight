package com.legaldocinsight.gateway.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class RequestLogPrivacyTest {
    @Test
    void statusExceptionDoesNotExposeItsReasonInResponse() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents/test").build());
        new com.legaldocinsight.gateway.exception.GlobalExceptionHandler().handle(exchange,
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "synthetic-secret-error")).block();
        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        assertFalse(exchange.getResponse().getBodyAsString().block().contains("synthetic-secret"));
    }

    @Test
    void unexpectedGatewayExceptionDoesNotLogItsMessageOrStackTrace() {
        var logger = (Logger) LoggerFactory.getLogger(com.legaldocinsight.gateway.exception.GlobalExceptionHandler.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents/test").build());
            new com.legaldocinsight.gateway.exception.GlobalExceptionHandler()
                .handle(exchange, new IllegalStateException("synthetic-secret-error")).block();
            assertFalse(events.list.isEmpty());
            for (var event : events.list) {
                assertFalse(event.getFormattedMessage().contains("synthetic-secret"));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void clientHeadersDoNotAppearInGatewayLogsOrContext() {
        var authLogger = (Logger) LoggerFactory.getLogger(JwtAuthFilter.class);
        var requestLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        authLogger.addAppender(events);
        requestLogger.addAppender(events);
        try {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.options("/api/documents/synthetic-secret-path")
                .header("X-Correlation-Id", "synthetic-secret-correlation")
                .header("X-User-Id", "synthetic-secret-user").build());
            new JwtAuthFilter().filter(exchange, next -> new RequestLoggingFilter().filter(next, end -> Mono.empty())).block();
            assertFalse(events.list.isEmpty());
            for (var event : events.list) {
                assertFalse(event.getFormattedMessage().contains("synthetic-secret"));
                assertTrue(event.getMDCPropertyMap().values().stream().noneMatch(v -> v.contains("synthetic-secret")));
            }
        } finally {
            authLogger.detachAppender(events);
            requestLogger.detachAppender(events);
            events.stop();
        }
    }
}
