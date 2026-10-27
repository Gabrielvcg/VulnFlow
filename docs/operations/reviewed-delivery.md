# Reviewed delivery: V11 and AWS promotion gates

CI must run the complete root Maven reactor on Ubuntu/Temurin 17 and Docker,
including agent and all PostgreSQL integration tests. The explicit suite gate
requires CommandUploadRetryIT (four tests), CommandUploadMigrationIT,
FlywayUpgradeIT and AwsExecutionPathIT, with no skipped Failsafe tests. A local
-DskipITs result does not meet this gate.

CommandUploadMigrationIT starts a disposable PostgreSQL 16.4 instance, upgrades
V10 legacy rows to V11, validates nullable identity, foreign key and CHECK
constraints, checks old column projections/inserts with the additive schema,
and restores a custom pg_dump into a second disposable database. It validates
Flyway and restored request/token/hash identity. This proves fixture/schema
compatibility; it is not a production backup or a run of an old application
binary. CommandUploadRetryIT additionally exercises persisted concurrent
reservation, accepted-token/null-lease fencing, transaction rollback, delayed
release and expired claim recovery. No production data or real AWS is used.

Production was observed in prod,aws mode during the delivery inventory, so
VPS images alone cannot deliver shared DynamoDB adapter changes. CI retains
vulnflow-lambda-processor-0.4.9.jar and its SHA-256 as the artifact
vulnflow-lambda-<source SHA> for fourteen days. Use only the successful final
promotion revision's artifact; a PR merge-test artifact does not identify the
subsequent main build. Terraform's old 0.4.8 path defaults are not release input.
Check the retained JAR and independently record its base64 SHA-256 for Lambda.

Before main merge (which currently automatically deploys the VPS), the release
owner must sequence a deployment hold or controlled window, confirm full CI,
record deployed Lambda CodeSha256/version/alias/event-source mapping and a
retrievable rollback package, and confirm the existing production PostgreSQL
backup plus a successful restore to a disposable database. Do not execute
Terraform apply, update Lambda or invoke production ingestion merely to test.

The event V1 contract, core parser, agent protocol and public upload outcomes
are unchanged by this package. Promote the new Lambda first after the exact
artifact/rollback gate is satisfied: it accepts existing V1 events and writes
the same DynamoDB schema. Then promote API/agent/web from the reviewed main
revision through the existing workflow and Flyway startup, preserving AWS mode
and volumes. If actual deployed Lambda inspection shows a contract divergence,
stop and review the version set instead of assuming this order is safe.

Rollback restores the immediately previous API images without removing V11's
nullable columns, and restores the separately recorded Lambda package/version
if that code was promoted. Do not switch processing mode, drop columns or
restore a production database blindly. Old application schema reads are tested;
old code may discard acknowledgement identity on subsequent updates, so rollback
protects service availability rather than guaranteeing new retry behavior.

After a separately authorized deployment, verify exact images/digests, Flyway
success, prod,aws, health and bounded read-only existing-result projections.
Do not treat successful container rollback or a green PR as production delivery.
No production mutation is performed by this document or the new CI steps.
