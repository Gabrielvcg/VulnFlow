# Console result reconciliation

When the console is enabled, a scheduled job checks up to 25 processing requests
per cycle. It advances through pages and starts again after reaching the end.
Each request is reconciled in its own transaction. A result-store failure leaves
the request pending for a later cycle and does not prevent other requests from
being checked. Both the detail endpoint and the job lock the request before updating it.

The delay is configurable with `vulnflow.ui.reconciliation-interval` (default `10s`;
environment variable `VULNFLOW_UI_RECONCILIATIONINTERVAL`). The job continues to
reconcile accepted work when new on-demand scans are disabled. It does not start
scans or republish messages, and makes no schema changes. AWS result reads use
the existing result reader and credentials.

Tests cover persisted local completion, cloud completion after a temporary read
failure, and cloud validation failure without opening the detail endpoint.
Rollback uses the previous immutable application release; completed request rows
remain valid for that release.
