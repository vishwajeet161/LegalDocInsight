package com.legaldocinsight.analysis_service.model;

import org.springframework.jdbc.core.JdbcTemplate;

/** Single-instance dispatcher. Recovery requires the prior process to have stopped. */
public final class AnalysisDispatcher {
    private final JdbcTemplate db;
    private final AnalysisWorker worker;
    private boolean ready;

    public AnalysisDispatcher(JdbcTemplate db, AnalysisWorker worker) {
        this.db = db;
        this.worker = worker;
    }

    public synchronized void recover() {
        db.update("UPDATE analysis_jobs SET status='PENDING' WHERE status='PROCESSING'");
        ready = true;
    }

    public synchronized void dispatch() {
        if (!ready) return;
        var jobs = db.queryForList("SELECT id FROM analysis_jobs WHERE status='PENDING' ORDER BY created_at, id LIMIT 1", String.class);
        if (!jobs.isEmpty()) worker.process(jobs.get(0));
    }
}
