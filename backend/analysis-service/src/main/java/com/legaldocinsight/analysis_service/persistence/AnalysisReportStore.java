package com.legaldocinsight.analysis_service.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence boundary for a future worker; no production analyzer is enabled here. */
@Repository
public class AnalysisReportStore {
    private final JdbcTemplate db;
    public AnalysisReportStore(JdbcTemplate db) { this.db = db; }

    @Transactional
    public void complete(String jobId, JsonNode report) {
        if (report == null || !report.isObject()
                || !nonempty(report, "schemaVersion") || !nonempty(report, "summary")
                || !nonempty(report, "modelId") || !nonempty(report, "promptVersion")
                || !report.path("clauses").isArray() || !report.path("obligations").isArray()
                || !report.path("risks").isArray() || !report.path("evidenceReferences").isArray()
                || !report.path("lawObservations").isArray()) throw new IllegalArgumentException("Invalid report");
        int changed = db.update("UPDATE analysis_jobs SET status='COMPLETED' WHERE id=? AND status IN ('PENDING','PROCESSING')", jobId);
        if (changed != 1) throw new IllegalStateException("Job is not available for completion");
        db.update("INSERT INTO analysis_reports(job_id, report_json) VALUES (?, ?)", jobId, report.toString());
    }

    private static boolean nonempty(JsonNode node, String name) {
        return node.path(name).isTextual() && !node.path(name).asText().isBlank();
    }
}
