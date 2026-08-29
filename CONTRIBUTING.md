# Contributing

## Before opening a change

- Keep credentials, private keys, certificates, report data, target references,
  `.env` files, and generated analysis artifacts out of the repository.
- Run the backend verification, agent tests, web tests/build, and Terraform
  validation relevant to the change.
- Keep local mode working without AWS credentials.
- Preserve the API-key boundary for machine integrations and the session/CSRF
  boundary for the private console.

## Pull requests

Describe the behavior changed, the tests run, and any operational or security
implications. Do not include production identifiers or screenshots containing
private telemetry.
