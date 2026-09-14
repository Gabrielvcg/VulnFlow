# Atomic console admission

Console scan creation takes PostgreSQL transaction advisory lock `(1447447628, 1)`
before checking quotas, cooldowns, Agent health and queue capacity. The lock is held
until the request insertion commits or rolls back, including across backend replicas.
Concurrent callers therefore see the preceding committed admission before deciding.

All limits, response codes, and target checks retain their existing meaning. The
lock protects the short database admission transaction; no scan or AWS call runs
inside it. PostgreSQL releases it automatically on rollback or connection loss.
No schema migration is required. Rollback restores the previous application images,
but also restores the prior concurrent-admission race.
