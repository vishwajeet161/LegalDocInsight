package com.legaldocinsight.analysis_service.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legaldocinsight.analysis_service.persistence.AnalysisReportStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;

/** Opt-in single-instance worker. Do not overlap an old and new process. */
@Component
@EnableScheduling
@ConditionalOnProperty(name="analysis.worker.enabled", havingValue="true")
public class AnalysisWorkerRuntime {
    private final AnalysisDispatcher dispatcher;

    public AnalysisWorkerRuntime(JdbcTemplate db, AnalysisReportStore reports, ObjectMapper json,
            @Value("${analysis.model.url:http://localhost:11434}") String url,
            @Value("${analysis.model.name:}") String name,
            @Value("${analysis.model.cloud:false}") boolean cloud,
            @Value("${analysis.model.api-key:}") String apiKey,
            @Value("${analysis.model.timeout:PT2M}") Duration timeout,
            @Value("${analysis.model.prompt-version:v1}") String promptVersion) {
        var client = new OllamaClient(URI.create(url), name, timeout, json, cloud, apiKey);
        dispatcher = new AnalysisDispatcher(db, new AnalysisWorker(db, reports, json, client::generate, name, promptVersion, 12000, 6));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() { dispatcher.recover(); }

    @Scheduled(fixedDelayString="${analysis.worker.poll-delay-ms:5000}")
    public void dispatch() { dispatcher.dispatch(); }
}
