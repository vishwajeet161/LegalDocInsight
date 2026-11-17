package com.legaldocinsight.document_service.config;

import jakarta.servlet.*;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class MdcFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        MDC.put("correlationId", UUID.randomUUID().toString());

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
