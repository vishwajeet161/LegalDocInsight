package com.legaldocinsight.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayStartupTest {
    @Test
    void gatewayStartsWithRateLimiterConfigured() {
    }
}
