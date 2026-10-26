# Command upload retry identity and reservation

## Acceptance and acknowledgement

A command upload is identified by request, claim token, asset and SHA-256 of validated
file bytes. Once associated, the original token is retained in claim_token as an
acknowledgement identity; claim_expires_at is null. Only PROCESSING, COMPLETED or
FAILED requests with a scan and uploaded_at can acknowledge this identity. A failed
processing result does not invalidate upload acceptance. Existing job/outbox outcome
mapping determines HTTP 200 (DUPLICATE/DEAD_LETTER) or 202 (queued/processing). AWS
publication state remains distinct from processing completion. No new response fields,
enums, authentication rules or error codes are introduced.

The retained token grants no start, heartbeat or agent-fail capability: those paths
still require a live lease. Post-processing failure preserves acknowledgement identity;
pre-acceptance failure and claim recovery clear obsolete claim identity. Legacy already
associated rows whose token was previously discarded cannot recover it and remain fenced.

## Validation and transaction boundaries

DefaultScanIngestionService prepares bytes once using the existing empty/size/MIME,
read, filename and SHA-256 implementation before reservation. No duplicate parser or
hasher is introduced. Body parsing remains asynchronous. Invalid/unreadable uploads do
not reserve or mutate the request. The route without a command retains its original
registration path. Preparation still requires the asset, as before.

Short request transactions acquire the existing pessimistic row lock to reserve,
associate or release. File reading, submission and report-storage work occur between
these transactions, not under an outer request transaction. Each reservation pins
asset/hash and assigns an independent attempt UUID. A valid first upload must match
the target asset; pending retries must match the pinned identity even if target metadata
changes. Association rechecks attempt, claim, asset/hash and existing scan/event under
the row lock. An already accepted association is an immutable no-op only for identical
scan/event identity. Conflicting claims, assets, content and events reject before new
registration/storage work.

A concurrent same-identity operation receives the existing generic HTTP 500 INTERNAL_ERROR
while the attempt is occupied. The agent's existing classification retries 5xx but treats
409 as permanent, so an in-flight attempt is deliberately not mislabeled STALE_SCAN_CLAIM.
Actual wrong identity continues to return the existing 409. No new HTTP error code is
needed. A subsequent call after association reads existing metadata without registration,
payload storage or deletion.

## Failure and uncertainty

On failure the controller asks a separate locked transaction to release only its attempt
UUID, current claim and unassociated live request. This frees the execution slot while
retaining asset/hash. It never resets a new/recovered operation or an accepted association.
The next same-identity attempt first uses profile-specific read-only job/outbox lookup;
a committed registration with a lost acknowledgement is associated without another upload.
If no complete registration is visible it uses the existing registration path. Different
bytes never take that fallback for a pinned claim. No payload compensation is added.
Release failures preserve the original exception with a suppressed cleanup failure.

This is not an atomic storage/request transaction or an exactly-once external-storage
protocol. A killed process or failed reservation commit acknowledgement can leave an
occupied attempt until existing lease recovery; a still-running/uncertain registration
must not be blindly stolen. Heartbeats can prolong a live claim, so operational recovery
of a genuinely stuck live agent still requires that agent to stop heartbeating and its
lease to expire. An uncertain storage-only write may remain retained under ADR-020.
The change prevents distinct replay payloads and ordinary caught failures from creating
new effects; it does not prove late commits impossible across real infrastructure.

## Persistence decision and verification

V11 adds nullable upload_asset_id, upload_content_hash and upload_attempt_id. The existing
scan association supplies accepted content identity, and claim_token supplies accepted
acknowledgement identity. Before association neither stores pending content, so two
identity columns are necessary to reject different replays after an uncertain failure.
The third UUID distinguishes operation ownership so delayed release cannot clear a later
attempt. No existing migration is edited and no new table is introduced. Old rows remain
valid; no discarded token is reconstructed. Deploy V11 before this application version.

CommandUploadRetryTest uses the real controller, request service/domain and ingestion
service with modeled repository/submission/storage ports and standalone multipart MockMvc.
It tests acceptance, lost responses, terminal processing, immutable metadata, wrong/stale
identity, validation/read failures, uncertain registration, association-read failure and
in-flight ordering. RegisteredUploadLookupTest exercises real local/AWS read-only lookup
implementations with repository/storage substitutes. UploadReservationOwnershipTest checks
late release and association ownership. These model ordering, not PostgreSQL isolation.
CommandUploadRetryIT covers real V11 persistence/reload and concurrent reservation locking
with PostgreSQL Testcontainers; it is compiled but NOT RUN under -DskipITs. Migration,
real database locks, rollback/commit visibility, AWS and deployment remain unverified.
