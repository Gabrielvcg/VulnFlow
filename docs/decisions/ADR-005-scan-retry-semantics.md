# ADR-005: Reuse failed scans and serialize retries

- Status: Accepted
- Date: 2026-07-29

## Context

The unique `(asset_id, content_hash)` constraint prevented duplicate completed
scans but also permanently blocked a valid retry after transient failure.

## Decision

Keep the unique constraint. Register through `INSERT ... ON CONFLICT DO
NOTHING`, lock the resulting row, return completed rows as duplicates, return
active rows as `202 ALREADY_PROCESSING`, and safely move failed rows back to
`PROCESSING`. A retry reuses the scan ID and removes incompatible old findings.

## Consequences

Only one request can claim an asset/hash at a time. Binary-identical files with
different names deduplicate; the same bytes for different assets do not.
Semantic JSON normalization is not attempted.
