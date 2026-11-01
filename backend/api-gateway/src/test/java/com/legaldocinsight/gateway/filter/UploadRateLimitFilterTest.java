package com.legaldocinsight.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UploadRateLimitFilterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void blocksExcessUploadsAndDoesNotTrustForwardedAddressByDefault() {
        var filter = new UploadRateLimitFilter(new UploadRateLimiter(1, 10, Duration.ofHours(1), 100), CLOCK);
        var first = upload("192.0.2.1", "203.0.113.1");
        var second = upload("192.0.2.1", "203.0.113.2");

        run(filter, first);
        run(filter, second);

        assertEquals(HttpStatus.NO_CONTENT, first.getResponse().getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
        assertNotNull(second.getResponse().getHeaders().getFirst("Retry-After"));
    }

    @Test
    void globalUploadBudgetAppliesAcrossClientsButNotStatusPolling() {
        var filter = new UploadRateLimitFilter(new UploadRateLimiter(10, 1, Duration.ofHours(1), 100), CLOCK);
        var status = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents/id")
            .remoteAddress(new InetSocketAddress("192.0.2.1", 1000)).build());
        var first = upload("192.0.2.1", null);
        var second = upload("192.0.2.2", null);

        run(filter, status);
        run(filter, first);
        run(filter, second);

        assertEquals(HttpStatus.NO_CONTENT, status.getResponse().getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, first.getResponse().getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
    }

    @Test
    void trustedProxyUsesNearestUntrustedForwardedAddress() {
        var filter = new UploadRateLimitFilter(new UploadRateLimiter(1, 10, Duration.ofHours(1), 100),
            CLOCK, List.of("127.0.0.1/32"));
        var first = upload("127.0.0.1", "192.0.2.1");
        var other = upload("127.0.0.1", "192.0.2.2");
        var spoofed = upload("127.0.0.1", "203.0.113.5, 192.0.2.1");

        run(filter, first);
        run(filter, other);
        run(filter, spoofed);

        assertEquals(HttpStatus.NO_CONTENT, first.getResponse().getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, other.getResponse().getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, spoofed.getResponse().getStatusCode());
    }

    @Test
    void pathParametersCannotBypassTheUploadBudget() {
        var filter = new UploadRateLimitFilter(new UploadRateLimiter(1, 10, Duration.ofHours(1), 100), CLOCK);
        var first = upload("192.0.2.1", null);
        var alternatePath = MockServerWebExchange.from(MockServerHttpRequest.post("/api/documents/upload;v=1")
            .remoteAddress(new InetSocketAddress("192.0.2.1", 1000)).build());

        run(filter, first);
        run(filter, alternatePath);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, alternatePath.getResponse().getStatusCode());
    }

    @Test
    void statusPollingHasItsOwnBudgetAndDoesNotConsumeUploadBudget() {
        var filter = new UploadRateLimitFilter(
            new UploadRateLimiter(1, 1, Duration.ofHours(1), 100),
            new UploadRateLimiter(1, 1, Duration.ofMinutes(1), 100), CLOCK, List.of());
        var first = statusRequest("192.0.2.1");
        var second = statusRequest("192.0.2.1");
        var upload = upload("192.0.2.1", null);

        run(filter, first);
        run(filter, second);
        run(filter, upload);

        assertEquals(HttpStatus.NO_CONTENT, first.getResponse().getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, upload.getResponse().getStatusCode());
    }

    private static MockServerWebExchange statusRequest(String address) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents/id")
            .remoteAddress(new InetSocketAddress(address, 1000)).build());
    }

    private static MockServerWebExchange upload(String address, String forwarded) {
        var request = MockServerHttpRequest.post("/api/documents/upload")
            .remoteAddress(new InetSocketAddress(address, 1000));
        if (forwarded != null) request.header("X-Forwarded-For", forwarded);
        return MockServerWebExchange.from(request.build());
    }

    private static void run(UploadRateLimitFilter filter, MockServerWebExchange exchange) {
        filter.filter(exchange, next -> {
            next.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return next.getResponse().setComplete();
        }).onErrorResume(Mono::error).block();
    }
}
