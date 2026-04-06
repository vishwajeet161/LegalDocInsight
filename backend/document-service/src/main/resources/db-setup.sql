-- ================================================================
-- Legal Document Analyzer — Database Setup
-- Run this once before starting Document Service
-- ================================================================

-- Create database
CREATE DATABASE legaldoc_db;

-- Connect to it
\c legaldoc_db;

-- Documents table
-- (Spring JPA will auto-create this via ddl-auto=update,
--  but having this script is good for production reference)
CREATE TABLE IF NOT EXISTS documents (
    id                  VARCHAR(36) PRIMARY KEY,
    original_file_name  VARCHAR(255) NOT NULL,
    stored_file_name    VARCHAR(255) NOT NULL,
    file_path           VARCHAR(512) NOT NULL,
    file_size           BIGINT NOT NULL,
    file_type           VARCHAR(100) NOT NULL,
    extracted_text      TEXT,
    page_count          INTEGER,
    character_count     INTEGER,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    uploaded_by         VARCHAR(36) NOT NULL,
    error_message       TEXT,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index for fast lookup by user
CREATE INDEX IF NOT EXISTS idx_documents_uploaded_by
    ON documents(uploaded_by);

-- Index for status filtering (reprocessing failed docs)
CREATE INDEX IF NOT EXISTS idx_documents_status
    ON documents(status);

-- ================================================================
-- Verify
-- ================================================================
SELECT 'Database setup complete' AS message;
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public';
