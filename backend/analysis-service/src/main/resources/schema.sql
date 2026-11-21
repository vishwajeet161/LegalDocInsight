CREATE TABLE IF NOT EXISTS analysis_jobs (
    id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    analysis_version VARCHAR(64) NOT NULL,
    extracted_text TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT analysis_job_unique UNIQUE (document_id, content_hash, analysis_version)
);
CREATE INDEX IF NOT EXISTS analysis_jobs_document_idx ON analysis_jobs (document_id, created_at DESC, id DESC);
CREATE TABLE IF NOT EXISTS analysis_reports (
    job_id VARCHAR(36) PRIMARY KEY REFERENCES analysis_jobs(id),
    report_json TEXT NOT NULL
);
