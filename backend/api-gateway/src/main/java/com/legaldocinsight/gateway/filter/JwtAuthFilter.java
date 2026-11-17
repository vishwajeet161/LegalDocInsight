package com.legaldocinsight.gateway.filter;

import com.legaldocinsight.gateway.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Slf4j
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RouteLocator routeLocator;

    private static final String FILTER_APPLIED = "JWT_FILTER_APPLIED";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

         // If this filter already ran for this exchange, skip it entirely
        if (exchange.getAttributes().containsKey(FILTER_APPLIED)) {
            return chain.filter(exchange);
        }
        exchange.getAttributes().put(FILTER_APPLIED, true);

        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod().name();

        String correlationId = UUID.randomUUID().toString();

        final String finalCorrelationId = correlationId;
        log.info("Incoming request: method={} correlationId={}", method, correlationId);

        // Always allow OPTIONS (CORS preflight)
        if (method.equals("OPTIONS")) {
            return proceedWithCorrelationId(exchange, chain, finalCorrelationId);
        }

        // Check route metadata to decide if auth is required
        return routeLocator.getRoutes()
            .filterWhen(route -> route.getPredicate().apply(exchange))
            .next()
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(matched -> {
                if (matched.isEmpty()) {
                    log.debug("No route matched, passing through");
                    return chain.filter(exchange);
                }
                var route = matched.get();
                Object requiresAuth = route.getMetadata().getOrDefault("requiresAuth", true);
                boolean isPublic = requiresAuth.toString().equalsIgnoreCase("false");

                if (isPublic) {
                    log.debug("Public route, skipping JWT");
                    return proceedWithCorrelationId(exchange, chain, finalCorrelationId);
                }

                return authenticate(exchange, chain, request, finalCorrelationId);
            });
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, GatewayFilterChain chain,
                                     ServerHttpRequest request, String correlationId) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or malformed Authorization header");
            return unauthorizedResponse(exchange, "Missing Authorization header");
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.isTokenValid(token)) {
            log.warn("Invalid JWT token");
            return unauthorizedResponse(exchange, "Invalid or expired token");
        }

        String userId = jwtUtil.extractUserId(token);
        String role = jwtUtil.extractRole(token);
        log.info("Authenticated request");

        ServerHttpRequest mutated = request.mutate()
            .header("X-Correlation-Id", correlationId)
            .header("X-User-Id", userId)
            .header("X-User-Role", role)
            .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private Mono<Void> proceedWithCorrelationId(ServerWebExchange exchange,
                                                  GatewayFilterChain chain, String correlationId) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
            .header("X-Correlation-Id", correlationId)
            .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json");
        var body = String.format("{\"error\": \"Unauthorized\", \"message\": \"%s\"}", message);
        var buffer = response.bufferFactory().wrap(body.getBytes());
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() { return -1; }
}
