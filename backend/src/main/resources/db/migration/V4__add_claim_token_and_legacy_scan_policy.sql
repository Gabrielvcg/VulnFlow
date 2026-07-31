ALTER TABLE ingestion_jobs
    ADD COLUMN claim_token UUID;

UPDATE scans AS scan
SET status = CASE
        WHEN job.attempt_count < job.max_attempts THEN 'RECEIVED'
        ELSE 'FAILED'
    END,
    scanner_version = CASE
        WHEN job.attempt_count < job.max_attempts THEN NULL
        ELSE scan.scanner_version
    END,
    started_at = CASE
        WHEN job.attempt_count < job.max_attempts THEN NULL
        ELSE scan.started_at
    END,
    completed_at = CASE
        WHEN job.attempt_count < job.max_attempts THEN NULL
        ELSE CURRENT_TIMESTAMP
    END,
    failure_reason = CASE
        WHEN job.attempt_count < job.max_attempts THEN NULL
        ELSE 'Processing claim invalidated during the 0.2.0 upgrade'
    END
FROM ingestion_jobs AS job
WHERE job.scan_id = scan.id
  AND job.status = 'PROCESSING';

UPDATE ingestion_jobs
SET status = CASE
        WHEN attempt_count < max_attempts THEN 'RETRY_WAIT'
        ELSE 'DEAD_LETTER'
    END,
    available_at = CURRENT_TIMESTAMP,
    locked_at = NULL,
    completed_at = CASE
        WHEN attempt_count < max_attempts THEN NULL
        ELSE CURRENT_TIMESTAMP
    END,
    last_error = 'Processing claim invalidated during the 0.2.0 upgrade',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'PROCESSING';

UPDATE scans AS scan
SET status = 'FAILED',
    completed_at = CURRENT_TIMESTAMP,
    failure_reason = 'Legacy scan payload is unavailable after the asynchronous upgrade'
WHERE scan.status IN ('RECEIVED', 'PROCESSING')
  AND NOT EXISTS (
      SELECT 1
      FROM ingestion_jobs AS job
      WHERE job.scan_id = scan.id
  );

ALTER TABLE ingestion_jobs
    ADD CONSTRAINT ck_ingestion_jobs_processing_claim CHECK (
        (
            status = 'PROCESSING'
            AND locked_at IS NOT NULL
            AND claim_token IS NOT NULL
        )
        OR
        (
            status <> 'PROCESSING'
            AND locked_at IS NULL
            AND claim_token IS NULL
        )
    ),
    ADD CONSTRAINT ck_ingestion_jobs_terminal_completion CHECK (
        status NOT IN ('COMPLETED', 'DEAD_LETTER')
        OR completed_at IS NOT NULL
    ),
    ADD CONSTRAINT ck_ingestion_jobs_retry_attempts CHECK (
        status <> 'RETRY_WAIT'
        OR attempt_count < max_attempts
    );
