# VulnFlow web console

## Security boundary

The web image serves two deliberately separate surfaces:

- `/` is static, sanitized, and performs no API request.
- `/login` and `/app/**` use `/api/ui/v1/**`, JDBC-backed sessions, an eight-hour inactivity timeout, `Secure`/`HttpOnly`/`SameSite=Lax` session cookies, and CSRF tokens.

The Agent and existing integrations continue to use `X-API-Key` on `/api/v1/**`. The browser bundle contains no API key, registry credential, AWS identifier, password, or production result.

## Roles

- `OPERATOR` can view the operational history and request scans only for enabled catalog targets.
- `ADMIN` additionally manages users and targets, reads the audit trail, and retries failed PostgreSQL publication-outbox entries.

There is no public registration, anonymous console account, email recovery, arbitrary target input, report upload, or SQS DLQ redrive.

## First administrator

1. Generate a BCrypt cost-12 hash outside the repository.
2. Set `VULNFLOW_UI_BOOTSTRAP_USERNAME` and `VULNFLOW_UI_BOOTSTRAP_PASSWORD_HASH`.
3. Enable `VULNFLOW_UI_ENABLED` and start the backend once.
4. Sign in and replace the temporary password when prompted.
5. Remove both bootstrap variables from the VPS runtime environment and restart the backend.

Bootstrap is skipped whenever any UI user already exists. A temporary password returned by user creation or rotation is displayed once by the console and is never recoverable.

## Polling and scope

Active scan details poll every two seconds and stop at `COMPLETED` or `FAILED`. Dashboard and operations views poll every ten and fifteen seconds respectively. The dashboard is explicitly limited to 30 days and 500 scans. Findings remain scoped to one selected scan.

PostgreSQL supplies the recent scan identities and control state. Local mode aggregates findings from PostgreSQL; AWS mode resolves those same bounded identities with DynamoDB `BatchGetItem`, in batches of 100, so the dashboard never presents PostgreSQL processing state as AWS result truth.

## Development

Run `npm install`, `npm test`, `npm run build`, and `npm run test:e2e` from `web/`. CI additionally enforces Lighthouse scores of at least 90 for performance, accessibility, best practices, and SEO. `npm run dev` proxies API calls to `http://localhost:8080`. The production image uses non-root Nginx, a read-only filesystem, an explicit health endpoint, and a restrictive CSP.
