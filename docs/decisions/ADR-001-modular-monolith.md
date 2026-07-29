# ADR-001: Start with a modular monolith

- Status: Accepted
- Date: 2026-07-29

## Context

VulnFlow needs asset, scan, finding, ingestion, and dashboard capabilities, but
phase one has one team, one database, and one local deployment unit. Separate
services would add network contracts, distributed transactions, and operational
work before those boundaries are proven.

## Decision

Build one Spring Boot application organized by feature. Keep controllers thin,
business behavior in services, persistence in repositories, and cross-cutting
HTTP behavior in `shared`. Isolate report parsing, risk calculation, and scan
ingestion behind interfaces.

## Consequences

Local execution and testing stay simple. Transactions can protect scan/finding
consistency. Future queue consumers can reuse application behavior. If a feature
later needs independent scaling or ownership, extraction remains possible but
will be driven by evidence.

