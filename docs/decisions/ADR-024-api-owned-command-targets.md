# ADR-024: API-owned command targets

## Decision

The authenticated API target catalog is the source of truth for remote commands.
An Agent with commands enabled trusts its configured server and does not require
duplicate image entries in its local schedule. Empty schedules are supported.
Machine owners retain command opt-in, registry credentials, process timeout,
concurrency, report and outbox limits. Trivy arguments include an option terminator.

The API assigns each new request to one selected or automatically chosen Agent.
Assignment survives lease recovery. Legacy requests remain compatible. V8 is an
additive migration; it must not be removed during image rollback.

## Consequences

New images can be registered and launched entirely through the existing console.
Local YAML entries schedule recurring scans only. Registry access is tested when
Trivy retrieves the image, not guaranteed by catalog registration. Trusting a
server allows that server to choose image references, including registry hosts.
Deploy an Agent only against a server trusted by its machine owner.

There is no automatic namespace discovery or Agent-group credential routing.
The existing shared machine API key also means Agent IDs are routing identifiers,
not independently authenticated machine identities.
