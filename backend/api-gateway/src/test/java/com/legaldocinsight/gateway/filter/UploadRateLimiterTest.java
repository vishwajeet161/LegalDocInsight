package com.legaldocinsight.gateway.filter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadRateLimiterTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsRequestsAbovePerIpLimitWithoutSpendingGlobalBudget() {
        var limiter = new UploadRateLimiter(2, 3, Duration.ofHours(1), 100);

        assertTrue(limiter.tryAcquire("192.0.2.1", START));
        assertTrue(limiter.tryAcquire("192.0.2.1", START));
        assertFalse(limiter.tryAcquire("192.0.2.1", START));
        assertTrue(limiter.tryAcquire("192.0.2.2", START));
    }

    @Test
    void globalLimitAppliesAcrossDifferentClientAddresses() {
        var limiter = new UploadRateLimiter(5, 2, Duration.ofHours(1), 100);

        assertTrue(limiter.tryAcquire("192.0.2.1", START));
        assertTrue(limiter.tryAcquire("192.0.2.2", START));
        assertFalse(limiter.tryAcquire("192.0.2.3", START));
    }

    @Test
    void allowsRequestsAfterTheWindowExpires() {
        var limiter = new UploadRateLimiter(1, 1, Duration.ofHours(1), 100);

        assertTrue(limiter.tryAcquire("192.0.2.1", START));
        assertFalse(limiter.tryAcquire("192.0.2.1", START.plusSeconds(3599)));
        assertTrue(limiter.tryAcquire("192.0.2.1", START.plusSeconds(3600)));
    }

    @Test
    void concurrentRequestsCannotExceedTheGlobalLimit() throws Exception {
        var limiter = new UploadRateLimiter(20, 5, Duration.ofHours(1), 100);
        var ready = new CountDownLatch(20);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(20)) {
            var results = IntStream.range(0, 20)
                .mapToObj(i -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return limiter.tryAcquire("192.0.2." + i, START);
                }))
                .toList();
            ready.await();
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) accepted++;
            }
            org.junit.jupiter.api.Assertions.assertEquals(5, accepted);
        }
    }
}
