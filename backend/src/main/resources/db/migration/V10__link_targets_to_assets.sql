INSERT INTO assets (id, name, type, external_reference, created_at, updated_at)
SELECT gen_random_uuid(), target.name, target.type, target.external_reference, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM ui_targets target
WHERE target.asset_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM assets asset
      WHERE asset.type = target.type
        AND asset.external_reference = target.external_reference
  )
ON CONFLICT (type, external_reference) DO NOTHING;

UPDATE ui_targets target
SET asset_id = asset.id,
    updated_at = CURRENT_TIMESTAMP
FROM assets asset
WHERE target.asset_id IS NULL
  AND asset.type = target.type
  AND asset.external_reference = target.external_reference;

ALTER TABLE ui_targets ALTER COLUMN asset_id SET NOT NULL;
