# Web reliability fixes

Scope: repair the concrete findings from the September 7 review in the current checkout, preserving existing uncommitted changes. No production deployment.

1. Serialize refunds on the original spend row; enforce one refund claim per spend with a dedicated primary-key table, preserving legacy refund records.
2. Store matchmaking task snapshots in SQL. Create snapshot and charge/reservation together; publish to the in-memory single-instance queue only after commit. Save report and terminal snapshot together. Import legacy disk snapshots once, fail visibly on storage errors, retain compensation evidence until settled.
3. Serialize create/delete/final-save paths; cancel and compensate active tasks on delete, clear questionnaire copies on terminal tasks and single-report deletion.
4. Scope metadata queries to the current schema. Preserve creation timestamps on recovery.
5. Replace overlapping polling with cancellable sequential polling, bounded request timeout, retry/backoff, and visible reconnect state.
6. Add transaction/concurrency/failure/recovery and frontend polling tests, run full existing gates, review changes, update AGENTS.md and WORKLOG.md.

Validation targets: concurrent refunds yield one credit; failed task insert rolls back charge; duplicate submits yield one task; failed refund remains retryable through restart; report and terminal snapshot commit/rollback together; deleted in-flight tasks cannot restore personal data; other schemas cannot affect migration; stopped pollers ignore stale responses.

## Outcome and review

Implemented all six scoped items. SQL task state and account/report mutations are transactional, while memory publication follows commit. Fault injection additionally covers a lost commit acknowledgement; compensation and deletion consult durable state. The admission lock is deliberately scoped to the existing single application instance, not a distributed queue.

Verification: 139 Java tests; 112 backend Node tests; four frontend request/polling tests included in the full frontend build; existing navigation/trial/WebView/PWA/DAL/PDF gates; dedicated MySQL 9.6 integration test on a separate disposable instance. Final targeted transaction/retention tests passed after the last durable-deletion review adjustment. No production changes.

Reviewed boundaries: charge rollback; concurrent/repeated refund; pending refund restart; queue admission; terminal commit visibility; committed-report reconciliation; in-flight deletion; questionnaire erasure; current-schema metadata; polling overlap, cancellation, errors and backoff. Deployment notes explicitly cover the SQL task migration and incompatibility of automatic code-only rollback to legacy disk-only releases.
