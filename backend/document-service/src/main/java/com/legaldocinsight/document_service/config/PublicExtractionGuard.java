package com.legaldocinsight.document_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("public")
class PublicExtractionGuard {
    PublicExtractionGuard() {
        throw new IllegalStateException(
            "Public extraction is disabled until parser isolation is enforced and verified");
    }
}
