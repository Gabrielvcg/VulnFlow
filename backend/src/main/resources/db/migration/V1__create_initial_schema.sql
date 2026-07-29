CREATE TABLE assets (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(32) NOT NULL,
    external_reference VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_assets_type
        CHECK (type IN ('HOST', 'CONTAINER_IMAGE', 'APPLICATION'))
);

CREATE TABLE scans (
    id UUID PRIMARY KEY,
    asset_id UUID NOT NULL REFERENCES assets(id),
    scanner VARCHAR(16) NOT NULL,
    scanner_version VARCHAR(100),
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_file_name VARCHAR(500) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    CONSTRAINT ck_scans_scanner CHECK (scanner IN ('TRIVY', 'SYFT')),
    CONSTRAINT ck_scans_status
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT uk_scans_asset_content_hash UNIQUE (asset_id, content_hash)
);

CREATE TABLE findings (
    id UUID PRIMARY KEY,
    scan_id UUID NOT NULL REFERENCES scans(id),
    asset_id UUID NOT NULL REFERENCES assets(id),
    vulnerability_id VARCHAR(255) NOT NULL,
    package_name VARCHAR(500) NOT NULL,
    installed_version VARCHAR(255),
    fixed_version VARCHAR(255),
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(1000),
    description TEXT,
    status VARCHAR(32) NOT NULL,
    known_exploited BOOLEAN NOT NULL DEFAULT FALSE,
    risk_score INTEGER NOT NULL,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_findings_severity
        CHECK (severity IN ('UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_findings_status
        CHECK (status IN ('OPEN', 'ACCEPTED', 'RESOLVED', 'FALSE_POSITIVE')),
    CONSTRAINT ck_findings_risk_score CHECK (risk_score BETWEEN 0 AND 100)
);

CREATE INDEX idx_scans_asset_id ON scans(asset_id);
CREATE INDEX idx_scans_content_hash ON scans(content_hash);
CREATE INDEX idx_findings_vulnerability_id ON findings(vulnerability_id);
CREATE INDEX idx_findings_asset_id ON findings(asset_id);
CREATE INDEX idx_findings_scan_id ON findings(scan_id);
CREATE INDEX idx_findings_severity ON findings(severity);
CREATE INDEX idx_findings_status ON findings(status);
