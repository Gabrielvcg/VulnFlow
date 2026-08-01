# CI/CD verification

The existing VPS publication and deployment path remains local-mode only. Pull requests and pushes first run the root Maven reactor, which compiles/tests/packages the processing core, AWS adapters, backend, agent, and Lambda artifact. PostgreSQL Testcontainers integration tests remain mandatory.

A separate job installs pinned Terraform, runs `fmt -check -recursive`, `init -backend=false`, and `validate`. It receives no AWS credentials and runs no `plan`, `apply`, or `destroy`. Only successful Java, container, and Terraform verification can unlock the unchanged `main`-only GHCR/VPS jobs.

The reactor now includes the mocked DynamoDB suite, hardened Lambda batch suite, and
`AwsExecutionPathIT`. That integration demo uses real PostgreSQL plus in-memory S3/SQS/DynamoDB fakes;
it verifies HTTP acceptance, durable outbox retry, concurrent publication claim, stable V1 event,
duplicate Lambda delivery, SHA-256 processing, partial batch failure, and result queries without network
access. CI disables EC2 metadata lookup and never supplies AWS credentials. Secret-oriented checks reject
committed private keys and common AWS access-key patterns; repository dependencies remain subject to the
existing dependency/container scanning jobs.

The backend Docker build context is now the repository root because its image compiles `processing-core` and `aws-adapters`. The runtime still activates `prod`, not `aws`; no AWS client is created and the VPS continues to use local report storage, PostgreSQL jobs, and `LocalIngestionWorker`.
