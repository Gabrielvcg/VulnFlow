CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY,
    scan_id UUID NOT NULL REFERENCES scans(id),
    payload_key VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_ingestion_jobs_scan UNIQUE (scan_id),
    CONSTRAINT uk_ingestion_jobs_payload_key UNIQUE (payload_key),
    CONSTRAINT ck_ingestion_jobs_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'DEAD_LETTER')
    ),
    CONSTRAINT ck_ingestion_jobs_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_ingestion_jobs_max_attempts CHECK (max_attempts > 0),
    CONSTRAINT ck_ingestion_jobs_attempt_limit CHECK (attempt_count <= max_attempts)
);

CREATE INDEX idx_ingestion_jobs_available
    ON ingestion_jobs(status, available_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT');

CREATE INDEX idx_ingestion_jobs_stale_processing
    ON ingestion_jobs(locked_at, id)
    WHERE status = 'PROCESSING';
