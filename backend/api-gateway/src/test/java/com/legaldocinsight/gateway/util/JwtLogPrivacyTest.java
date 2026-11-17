package com.legaldocinsight.gateway.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtLogPrivacyTest {
    @Test
    void invalidJwtDoesNotLogAttackerControlledHeaderOrException() {
        var logger = (Logger) LoggerFactory.getLogger(JwtUtil.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var jwt = new JwtUtil();
            ReflectionTestUtils.setField(jwt, "secret", "synthetic-key-at-least-32-bytes-long");
            var header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"synthetic-sensitive-token-data\"}".getBytes(StandardCharsets.UTF_8));
            assertFalse(jwt.isTokenValid(header + ".e30.e30"));
            assertFalse(events.list.isEmpty());
            for (var event : events.list) {
                assertFalse(event.getFormattedMessage().contains("synthetic-sensitive-token-data"));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
