# ADR-WRK-002 - Atomic owner expiry evaluation

Team implementation decision, 2026-10-12. This belongs to HU-WRK-001;
the ADR sequence number does not mean low-stock HU-WRK-002 is implemented.
Storage dependency: mis-inventory-db expiry-alert changeset, merged in 7e49046.

Inventory evaluates one owned lot through ExpiryAlerts. The caller supplies only
actor/lot identity and window (1..365 days), never balance, expiry or business date.
Classification uses registered physical units and owner time in America/Bogota:
positive stock expires on the expiry day itself; the future window is inclusive.
Zero/pending-only stock or dates outside the window create no new alert.

JdbcExpiryAlerts locks the lot with the same row-lock ordering as stock exits,
then reads existing alert evidence. Time is sampled after acquiring the locks;
the decision and upsert share one transaction. Concurrent evaluators serialize,
and an evaluation waiting for an exit sees its committed balance.
No medication/supplier database read or stock/movement/outbox write is introduced.

The unique lot upsert preserves ID and first detection. Repeated evaluation
refreshes audit/snapshot fields without duplicate alerts. Ineligible existing
alerts resolve without deletion; their resolution time is retained on repeats.
If eligibility returns (for example a policy-window change), the same record
reopens with its first detection retained and resolution cleared. This is automatic
evaluation, not a new manual dismissal or medication-reactivation feature.
A regressed owner clock is rejected with 409 EVALUATION_CLOCK_REGRESSED rather
than silently moving evidence backward; the caller must wait for clock recovery.

The repository uses the existing transactional Spring/JDBC pattern. Direct tests
wrap calls in the real transaction manager and use inventory_app on a disposable
seven-changeset database. Three policy and eight native tests cover boundaries,
midnight, pending stock, replay, resolution/reopen, rollback and concurrent exits/jobs.

There is no HTTP route in this PR: authorization, machine trigger, batch/scheduled
evaluation, public alert reads and live root deployment remain subsequent work.
Worker must call the owner API once added, never acquire database access. The
low-stock flag remains unchanged. No HU closure or live dev/qa/main migration claimed.
