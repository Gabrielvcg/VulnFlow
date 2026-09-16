-- Remove only historical smoke-test records. Real demo schedules and portfolio scans are preserved.
CREATE TEMPORARY TABLE synthetic_assets ON COMMIT DROP AS
SELECT id FROM assets
WHERE external_reference LIKE 'portfolio-e2e:%'
   OR external_reference LIKE 'portfolio-debug%'
   OR external_reference LIKE 'release-verification:%';

CREATE TEMPORARY TABLE synthetic_scans ON COMMIT DROP AS
SELECT id FROM scans WHERE asset_id IN (SELECT id FROM synthetic_assets);

DELETE FROM ui_scan_requests
WHERE scan_id IN (SELECT id FROM synthetic_scans)
   OR target_id IN (
       SELECT id FROM ui_targets
       WHERE external_reference LIKE 'portfolio-e2e:%'
          OR external_reference LIKE 'portfolio-debug%'
          OR external_reference LIKE 'release-verification:%'
   );
DELETE FROM ui_targets
WHERE external_reference LIKE 'portfolio-e2e:%'
   OR external_reference LIKE 'portfolio-debug%'
   OR external_reference LIKE 'release-verification:%';
DELETE FROM findings WHERE scan_id IN (SELECT id FROM synthetic_scans);
DELETE FROM ingestion_jobs WHERE scan_id IN (SELECT id FROM synthetic_scans);
DELETE FROM aws_publication_outbox WHERE scan_id IN (SELECT id FROM synthetic_scans);
DELETE FROM scans WHERE id IN (SELECT id FROM synthetic_scans);
DELETE FROM assets WHERE id IN (SELECT id FROM synthetic_assets);
