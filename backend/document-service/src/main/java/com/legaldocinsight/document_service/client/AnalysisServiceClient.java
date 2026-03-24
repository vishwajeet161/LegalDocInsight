package com.legaldocinsight.document_service.client;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class AnalysisServiceClient {

    private final WebClient webClient;

    public AnalysisServiceClient() {
        this.webClient = WebClient.builder()
                .baseUrl("http://localhost:8082")
                .build();
    }

    public void sendForAnalysis(String documentId, String text) {

        webClient.post()
                .uri("/analysis/analyze")
                .bodyValue(Map.of(
                        "documentId", documentId,
                        "text", text
                ))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe();
    }
}