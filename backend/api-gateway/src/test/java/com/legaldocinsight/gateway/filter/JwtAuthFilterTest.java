package com.legaldocinsight.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {
    @Test
    void publicRouteInvokesTheGatewayChainOnce() {
        var route = mock(Route.class);
        when(route.getPredicate()).thenReturn(exchange -> Mono.just(true));
        when(route.getMetadata()).thenReturn(Map.of("requiresAuth", false));
        var locator = mock(RouteLocator.class);
        when(locator.getRoutes()).thenReturn(Flux.just(route));
        var filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "routeLocator", locator);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/documents/upload").build());
        var calls = new AtomicInteger();

        filter.filter(exchange, next -> {
            calls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertEquals(1, calls.get());
    }
}
