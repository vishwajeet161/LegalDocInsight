package com.legaldocinsight.document_service.config;

import com.legaldocinsight.document_service.service.impl.ClamAvScanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ScannerConfig {
    @Bean
    ClamAvScanner clamAvScanner(@Value("${document.scanner.host:127.0.0.1}") String host,
                                @Value("${document.scanner.port:3310}") int port,
                                @Value("${document.scanner.timeout-ms:10000}") long timeoutMillis) {
        return new ClamAvScanner(host, port, Duration.ofMillis(timeoutMillis));
    }
}
