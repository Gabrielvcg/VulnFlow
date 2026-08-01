CREATE TABLE aws_publication_outbox (
    event_id UUID PRIMARY KEY,
    scan_id UUID NOT NULL REFERENCES scans(id),
    payload_key VARCHAR(1024) NOT NULL,
    event_json VARCHAR(4000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at TIMESTAMP WITH TIME ZONE,
    claim_token UUID,
    published_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_aws_publication_outbox_scan UNIQUE (scan_id),
    CONSTRAINT uk_aws_publication_outbox_payload UNIQUE (payload_key),
    CONSTRAINT ck_aws_publication_outbox_status CHECK (
        status IN ('PUBLISH_PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')
    ),
    CONSTRAINT ck_aws_publication_outbox_attempts CHECK (
        attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts
    ),
    CONSTRAINT ck_aws_publication_outbox_lock CHECK (
        (status = 'PUBLISHING' AND locked_at IS NOT NULL AND claim_token IS NOT NULL)
        OR (status <> 'PUBLISHING' AND locked_at IS NULL AND claim_token IS NULL)
    ),
    CONSTRAINT ck_aws_publication_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    )
);

CREATE INDEX idx_aws_publication_outbox_claimable
    ON aws_publication_outbox(available_at, created_at, event_id)
    WHERE status = 'PUBLISH_PENDING';

CREATE INDEX idx_aws_publication_outbox_stale
    ON aws_publication_outbox(locked_at, event_id)
    WHERE status = 'PUBLISHING';
