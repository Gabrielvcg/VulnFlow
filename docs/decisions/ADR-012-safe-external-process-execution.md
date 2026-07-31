# ADR-012: Safe external Trivy execution

## Status

Accepted for VulnFlow 0.3.0.

## Decision

Invoke Trivy through `ProcessBuilder` with an immutable list of individual
arguments. Target references remain one opaque argument. Never invoke a shell
or construct a command string. Generate output paths internally beneath the
configured temporary directory.

Drain stdout and stderr concurrently with a fixed capture limit, enforce a
timeout and exit code, bound and parse the JSON file, then delete the temporary
after the durable outbox copy. Startup performs a bounded `trivy --version`
check and fails rather than entering a retry loop when the executable is absent.

## Consequences

Shell metacharacters in image references cannot become commands. The process
still inherits the service account's environment and network access, so
operators must avoid credentials embedded in image references and apply host
least privilege. Trivy installation remains an explicit operator action.
