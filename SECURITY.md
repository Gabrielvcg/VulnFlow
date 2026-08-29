# Security Policy

## Scope

VulnFlow is a security engineering project for controlled vulnerability-report
ingestion and processing. The public repository contains source code, example
configuration, and synthetic sample reports only. Production credentials,
targets, reports, cloud identifiers, and runtime configuration are out of
scope and must never be committed.

## Reporting a vulnerability

Please do not disclose security issues in a public issue. Contact the
maintainer privately through the contact details on [vacaro.es](https://vacaro.es)
and include a reproducible description, affected component, and impact.

Do not test the live VulnFlow service or any target without explicit
authorization. The public landing page is a case study, not a permission to
scan.

## Supported releases

Only the latest `main` revision is actively maintained for this portfolio
demonstration. Deployments use immutable image references and keep runtime
secrets outside Git.
