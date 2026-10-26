ALTER TABLE ui_scan_requests
    ADD COLUMN upload_asset_id UUID REFERENCES assets(id),
    ADD COLUMN upload_content_hash VARCHAR(64),
    ADD COLUMN upload_attempt_id UUID;

ALTER TABLE ui_scan_requests ADD CONSTRAINT ck_ui_scan_requests_upload_identity CHECK (
    (upload_asset_id IS NULL AND upload_content_hash IS NULL AND upload_attempt_id IS NULL)
    OR (upload_asset_id IS NOT NULL AND upload_content_hash IS NOT NULL
        AND length(upload_content_hash) = 64)
);
