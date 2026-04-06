package com.legaldocinsight.document_service.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@Order(1)
public class MdcFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        String correlationId = httpRequest.getHeader("X-Correlation-Id");
        String userId        = httpRequest.getHeader("X-User-Id");
        String userRole      = httpRequest.getHeader("X-User-Role");

        if (correlationId != null) MDC.put("correlationId", correlationId);
        if (userId != null)        MDC.put("userId", userId);
        if (userRole != null)      MDC.put("userRole", userRole);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
