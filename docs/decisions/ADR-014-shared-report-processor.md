# ADR-014: Shared vulnerability report processor

Status: Accepted for 0.4.0.

## Decision

Move integrity verification, Trivy parsing, normalization, and risk calculation into the pure `processing-core` Maven module. Both `IngestionJobProcessor` and `SqsVulnerabilityReportHandler` call the same `VulnerabilityReportProcessor` and receive `ProcessedVulnerabilityReport` values.

The processor has no Spring, JPA, HTTP, filesystem, AWS SDK, Lambda, scheduling, lock, or queue dependency. Storage loading and result persistence remain adapter responsibilities.

## Consequences

There is one implementation of report rules and one contract test surface. JPA maps the core severity enum to its persistence enum at the boundary. Changing a normalization rule affects both runtimes and therefore requires core tests and compatibility review.
