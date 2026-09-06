# Console session revocation

Disabling a console account or rotating its password invalidates its persisted
Spring sessions after the account transaction commits. Changing a password from
the console invalidates other sessions while retaining the current browser session.
Failed account transactions do not trigger revocation.

The machine API key, report ingestion, AWS processing, and database schema are
unchanged. In-flight requests that already passed authorization may finish; this
does not cancel running scans. Integration tests exercise multiple browser
sessions and ensure an administrator's separate session remains usable.

Deploy through the existing immutable CI release workflow. Rollback uses the
previous release manifest and requires no schema rollback. Revoked sessions stay
revoked after rollback; users can sign in again with their current credentials.
