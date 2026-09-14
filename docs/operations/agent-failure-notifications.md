# Agent failure notifications

Command reports in DEAD_LETTER remain available for investigation and continue to
count against outbox storage limits. They no longer prevent the Agent from claiming
new commands. A successful failure notification creates an atomic `failure-reported`
sidecar in the existing item directory, so restarting the Agent does not notify again.
Unavailable notifications are retried without blocking command polling.

The backend accepts repeated failure notifications for the same assigned Agent and
already failed request. This covers a lost HTTP response or a crash before the sidecar
is persisted. Active and completed requests retain their fencing checks.

Report metadata and statuses are unchanged. The previous Agent ignores the sidecar,
so application rollback requires no data conversion or removal. Retained failures
still require operator review; this change does not delete or automatically replay them.
