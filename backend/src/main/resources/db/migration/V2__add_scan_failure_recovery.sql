ALTER TABLE scans
    ADD COLUMN failure_reason VARCHAR(500);

CREATE INDEX idx_scans_processing_started_at
    ON scans(started_at)
    WHERE status = 'PROCESSING';
