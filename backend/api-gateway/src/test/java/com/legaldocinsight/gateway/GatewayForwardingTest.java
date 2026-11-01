package com.legaldocinsight.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayForwardingTest {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0)
        .handle((request, response) -> {
            CALLS.incrementAndGet();
            return response.status(200).sendString(reactor.core.publisher.Mono.just("ok"));
        }).bindNow();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("DOCUMENT_SERVICE_URL", () -> "http://127.0.0.1:" + UPSTREAM.port());
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.disposeNow();
    }

    @Test
    void publicUploadIsForwardedOnce() {
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build()
            .post().uri("/api/documents/upload").contentType(MediaType.TEXT_PLAIN)
            .bodyValue("synthetic").exchange().expectStatus().isOk();
        assertEquals(1, CALLS.get());
    }
}
