WITH duplicate_assets AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY type, external_reference
               ORDER BY created_at, id
           ) AS duplicate_rank
    FROM assets
    WHERE external_reference IS NOT NULL
)
UPDATE assets AS asset
SET external_reference = NULL,
    updated_at = CURRENT_TIMESTAMP
FROM duplicate_assets AS duplicate
WHERE duplicate.id = asset.id
  AND duplicate.duplicate_rank > 1;

ALTER TABLE assets
    ADD CONSTRAINT uk_assets_type_external_reference
        UNIQUE (type, external_reference);
