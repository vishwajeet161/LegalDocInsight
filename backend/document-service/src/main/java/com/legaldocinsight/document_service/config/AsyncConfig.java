package com.legaldocinsight.document_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@EnableAsync
@EnableScheduling
@Configuration
public class AsyncConfig {

    @Bean(name = "extractionExecutor")
    public Executor extractionExecutor(@Value("${document.extraction.max-concurrent:2}") int concurrency,
                                       @Value("${document.extraction.queue-capacity:50}") int queueCapacity) {
        if (concurrency < 1 || queueCapacity < 0) throw new IllegalArgumentException("Invalid extraction capacity");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("tika-extraction-");
        executor.initialize();
        return executor;
    }
}
