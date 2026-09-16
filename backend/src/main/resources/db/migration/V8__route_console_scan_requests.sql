ALTER TABLE ui_scan_requests ADD COLUMN requested_agent_id VARCHAR(100);
CREATE INDEX ix_ui_scan_requests_agent_pending
    ON ui_scan_requests (requested_agent_id, requested_at, id) WHERE status = 'REQUESTED';
