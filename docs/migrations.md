# Database migrations

## V4 claim tokens and legacy scan policy

V4 upgrades both a clean installation and databases that already applied V3.
It adds the nullable UUID `claim_token` column and SQL checks that enforce:

- `PROCESSING` requires both `locked_at` and `claim_token`;
- non-processing states retain neither an active lock nor claim token;
- terminal jobs have `completed_at`;
- `RETRY_WAIT` retains at least one available attempt;
- the V3 attempt bounds remain active.

The column is nullable because only `PROCESSING` owns a claim. A claim token is
created by application code in the same transaction as the transition to
`PROCESSING`.

### Existing 0.2.0 jobs

An existing `PROCESSING` row predates token fencing and cannot safely retain
ownership. V4 invalidates it:

- with attempts remaining, job becomes `RETRY_WAIT` and scan `RECEIVED`;
- with no attempts remaining, job becomes `DEAD_LETTER` and scan `FAILED`.

The next claim creates a new UUID token. Other existing job states receive a
null token and remain unchanged.

### Scans created before persistent report storage

V1/V2 scans can exist without an ingestion job or retained report bytes:

- `COMPLETED` remains a valid deduplication result and gets no synthetic job;
- `RECEIVED` and `PROCESSING` without jobs become `FAILED` because there is no
  payload from which processing can resume;
- existing `FAILED` remains failed;
- re-uploading any non-completed legacy scan stores the newly supplied payload,
  changes the scan to `RECEIVED`, and creates exactly one job under the existing
  `(asset_id, content_hash)` lock.

No historical job is created without real payload bytes. The unavoidable trade
off is that an interrupted 0.1.1 scan cannot resume automatically after upgrade;
the original report must be uploaded again.

`FlywayUpgradeIT` applies V1/V2, inserts representative legacy rows, applies V3
and V4, and verifies the resulting policy with PostgreSQL 16.4.
