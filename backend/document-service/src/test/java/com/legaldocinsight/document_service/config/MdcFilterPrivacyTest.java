package com.legaldocinsight.document_service.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class MdcFilterPrivacyTest {
    @Test
    void untrustedHeadersNeverEnterLoggingContext() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "synthetic-secret-correlation");
        request.addHeader("X-User-Id", "synthetic-secret-user");
        request.addHeader("X-User-Role", "synthetic-secret-role");
        new MdcFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var context = MDC.getCopyOfContextMap();
            assertTrue(context == null || context.values().stream().noneMatch(v -> v.startsWith("synthetic-secret")));
        });
        assertNull(MDC.getCopyOfContextMap());
    }
}
