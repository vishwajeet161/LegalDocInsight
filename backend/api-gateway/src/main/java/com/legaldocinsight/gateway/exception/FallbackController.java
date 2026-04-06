package com.legaldocinsight.gateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/document")
    public ResponseEntity<Map<String, Object>> documentServiceFallback() {
        log.error("Document service is unavailable — circuit breaker triggered");
        return buildFallbackResponse("Document Service is currently unavailable. Please try again later.");
    }

    @RequestMapping("/analysis")
    public ResponseEntity<Map<String, Object>> analysisServiceFallback() {
        log.error("Analysis service is unavailable — circuit breaker triggered");
        return buildFallbackResponse("Analysis Service is currently unavailable. Please try again later.");
    }

    @RequestMapping("/chat")
    public ResponseEntity<Map<String, Object>> chatServiceFallback() {
        log.error("Chat service is unavailable — circuit breaker triggered");
        return buildFallbackResponse("Chat Service is currently unavailable. Please try again later.");
    }

    private ResponseEntity<Map<String, Object>> buildFallbackResponse(String message) {
        Map<String, Object> body = Map.of(
            "status", "SERVICE_UNAVAILABLE",
            "message", message,
            "timestamp", LocalDateTime.now().toString()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}