package com.legaldocinsight.gateway.filter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Tracks the shared and per-client upload budgets for one gateway instance. */
final class UploadRateLimiter {
    private final int perIpLimit;
    private final int globalLimit;
    private final Duration window;
    private final int maxKeys;
    private final ArrayDeque<Instant> globalRequests = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Instant>> clientRequests = new HashMap<>();

    UploadRateLimiter(int perIpLimit, int globalLimit, Duration window, int maxKeys) {
        if (perIpLimit < 1 || globalLimit < 1 || maxKeys < 1
                || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate limits and window must be positive");
        }
        this.perIpLimit = perIpLimit;
        this.globalLimit = globalLimit;
        this.window = window;
        this.maxKeys = maxKeys;
    }

    synchronized boolean tryAcquire(String clientIp, Instant now) {
        Objects.requireNonNull(clientIp, "clientIp");
        Instant cutoff = Objects.requireNonNull(now, "now").minus(window);
        discardExpired(globalRequests, cutoff);
        ArrayDeque<Instant> client = clientRequests.get(clientIp);
        if (client != null) {
            discardExpired(client, cutoff);
        } else if (clientRequests.size() >= maxKeys) {
            clientRequests.values().removeIf(requests -> {
                discardExpired(requests, cutoff);
                return requests.isEmpty();
            });
            if (clientRequests.size() >= maxKeys) {
                return false;
            }
        }
        if (globalRequests.size() >= globalLimit
                || (client != null && client.size() >= perIpLimit)) {
            return false;
        }
        if (client == null) {
            client = new ArrayDeque<>();
            clientRequests.put(clientIp, client);
        }
        globalRequests.addLast(now);
        client.addLast(now);
        return true;
    }

    long retryAfterSeconds() {
        return Math.max(1, window.toSeconds());
    }

    private static void discardExpired(ArrayDeque<Instant> requests, Instant cutoff) {
        while (!requests.isEmpty() && !requests.peekFirst().isAfter(cutoff)) {
            requests.removeFirst();
        }
    }
}
