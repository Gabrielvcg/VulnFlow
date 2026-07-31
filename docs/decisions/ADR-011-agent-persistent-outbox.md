# ADR-011: Filesystem-backed agent outbox

## Status

Accepted for VulnFlow 0.3.0.

## Decision

Persist every completed local scan beneath a random UUID directory before
attempting network delivery. Report and metadata creation use temporary files
and atomic same-filesystem renames. Upload claims transition metadata to
`UPLOADING`; startup recovers interrupted claims to `RETRY_WAIT`.

Successful reports are retained for a configurable period. Retention never
removes pending, retrying, uploading, or dead-letter work. Count and byte caps
reject new items without silently deleting existing evidence. SHA-256 detects
post-scan modification before upload.

## Consequences

The agent tolerates restarts and backend outages without a database dependency.
One filesystem directory must have one owning process; cross-process and NFS
locking are outside this local phase. An item scanned while fully offline has a
temporarily null asset ID until idempotent resolution succeeds.
