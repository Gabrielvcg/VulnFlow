# CI/CD verification

The existing VPS publication and deployment path remains local-mode only. Pull requests and pushes first run the root Maven reactor, which compiles/tests/packages the processing core, AWS adapters, backend, agent, and Lambda artifact. PostgreSQL Testcontainers integration tests remain mandatory.

A separate job installs pinned Terraform, runs `fmt -check -recursive`, `init -backend=false`, and `validate`. It receives no AWS credentials and runs no `plan`, `apply`, or `destroy`. Only successful Java, container, and Terraform verification can unlock the unchanged `main`-only GHCR/VPS jobs.

The backend Docker build context is now the repository root because its image compiles `processing-core` and `aws-adapters`. The runtime still activates `prod`, not `aws`; no AWS client is created and the VPS continues to use local report storage, PostgreSQL jobs, and `LocalIngestionWorker`.
