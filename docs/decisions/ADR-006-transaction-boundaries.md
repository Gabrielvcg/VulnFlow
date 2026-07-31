# ADR-006: Isolate scan lifecycle transactions

- Status: Superseded by ADR-009 for asynchronous ingestion
- Date: 2026-07-29

## Context

This record describes the synchronous 0.1.1 implementation. The asynchronous
transaction boundaries are defined by ADR-009 and `docs/architecture.md`.

Scan registration must survive parser failure, findings and completion must be
atomic, and failure recording must not roll back with the original operation.
Post-commit response failures must never change a completed scan to failed.

## Decision

Use three separate Spring beans and `REQUIRES_NEW` methods:

- `ScanRegistrationService.registerProcessing()`
- `IngestionPersistenceService.complete()`
- `ScanFailureService.markFailed()`

The ingestion orchestrator catches only parser and completion failures. It
preserves the original exception if failure recording also fails.

## Consequences

Transaction boundaries are explicit and proxy-safe. Response construction is
outside failure marking. A process crash can still leave `PROCESSING`; the
idempotent `ScanRecoveryService` identifies timed-out rows for later invocation.
