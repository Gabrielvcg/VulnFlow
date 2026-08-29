CREATE TABLE ui_users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    password_change_required BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_ui_users_username UNIQUE (username),
    CONSTRAINT ck_ui_users_role CHECK (role IN ('ADMIN', 'OPERATOR')),
    CONSTRAINT ck_ui_users_failed_attempts CHECK (failed_login_attempts >= 0)
);

CREATE TABLE ui_targets (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(32) NOT NULL,
    external_reference VARCHAR(500) NOT NULL,
    asset_id UUID REFERENCES assets(id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES ui_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_ui_targets_reference UNIQUE (type, external_reference),
    CONSTRAINT ck_ui_targets_type CHECK (type = 'CONTAINER_IMAGE')
);

CREATE TABLE ui_agents (
    id VARCHAR(100) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    current_request_id UUID,
    outbox_pending INTEGER NOT NULL DEFAULT 0,
    outbox_dead_letters INTEGER NOT NULL DEFAULT 0,
    outbox_bytes BIGINT NOT NULL DEFAULT 0,
    disk_free_bytes BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    last_heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ui_agents_status CHECK (status IN ('IDLE', 'BUSY', 'DEGRADED'))
);

INSERT INTO ui_targets (id, name, type, external_reference, asset_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), name, type, external_reference, id, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM assets
WHERE type = 'CONTAINER_IMAGE' AND external_reference IS NOT NULL
ON CONFLICT (type, external_reference) DO NOTHING;

CREATE TABLE ui_scan_requests (
    id UUID PRIMARY KEY,
    target_id UUID NOT NULL REFERENCES ui_targets(id),
    requested_by UUID NOT NULL REFERENCES ui_users(id),
    agent_id VARCHAR(100) REFERENCES ui_agents(id),
    status VARCHAR(20) NOT NULL,
    claim_token UUID,
    claim_expires_at TIMESTAMP WITH TIME ZONE,
    heartbeat_at TIMESTAMP WITH TIME ZONE,
    recovery_attempts INTEGER NOT NULL DEFAULT 0,
    scan_id UUID REFERENCES scans(id),
    event_id UUID,
    safe_error VARCHAR(500),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claimed_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    uploaded_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ui_scan_requests_status CHECK (
        status IN ('REQUESTED', 'CLAIMED', 'RUNNING', 'UPLOADING', 'PROCESSING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_ui_scan_requests_claim CHECK (
        (status IN ('CLAIMED', 'RUNNING', 'UPLOADING') AND claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
        OR status NOT IN ('CLAIMED', 'RUNNING', 'UPLOADING')
    ),
    CONSTRAINT ck_ui_scan_requests_recovery CHECK (recovery_attempts BETWEEN 0 AND 2)
);

ALTER TABLE ui_agents
    ADD CONSTRAINT fk_ui_agents_current_request
    FOREIGN KEY (current_request_id) REFERENCES ui_scan_requests(id);

CREATE INDEX idx_ui_scan_requests_queue ON ui_scan_requests(requested_at, id) WHERE status = 'REQUESTED';
CREATE INDEX idx_ui_scan_requests_user_time ON ui_scan_requests(requested_by, requested_at DESC);
CREATE INDEX idx_ui_scan_requests_target_time ON ui_scan_requests(target_id, requested_at DESC);
CREATE INDEX idx_ui_scan_requests_claim_expiry ON ui_scan_requests(claim_expires_at) WHERE claim_expires_at IS NOT NULL;

CREATE TABLE ui_audit_events (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES ui_users(id),
    actor_username VARCHAR(100),
    action VARCHAR(64) NOT NULL,
    subject_type VARCHAR(64),
    subject_id VARCHAR(100),
    outcome VARCHAR(16) NOT NULL,
    source_address_hash VARCHAR(64),
    details VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ui_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
);

CREATE INDEX idx_ui_audit_created_at ON ui_audit_events(created_at DESC);

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INTEGER NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
