package com.legaldocinsight.document_service;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/documents/health")
    public String health() {
        return "Document Service is UP";
    }
}
