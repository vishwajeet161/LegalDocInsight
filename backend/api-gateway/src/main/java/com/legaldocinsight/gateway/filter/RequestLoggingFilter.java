package com.legaldocinsight.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long startTime = System.currentTimeMillis();

        return chain.filter(exchange).doFinally(signalType -> {
            ServerHttpResponse response = exchange.getResponse();
            long duration = System.currentTimeMillis() - startTime;

            int statusCode = response.getStatusCode() != null
                ? response.getStatusCode().value()
                : 0;

            // Log at WARN level for 4xx/5xx, INFO for 2xx/3xx
            if (statusCode >= 400) {
                log.warn("method={} status={} duration={}ms",
                    request.getMethod(),
                    statusCode,
                    duration);
            } else {
                log.info("method={} status={} duration={}ms",
                    request.getMethod(),
                    statusCode,
                    duration);
            }
        });
    }

    @Override
    public int getOrder() {
        return 0; // Run after JwtAuthFilter (-1)
    }
}
