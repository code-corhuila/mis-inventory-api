# WRK-001 - Owner evaluation acceptance

Operator-run Maven/Java 17/PostgreSQL acceptance, 2026-10-12. The existing
Test-Persistence.ps1 created a random mis-inv-api-check-* project and applied the
seven official DB changesets. Maven verify passed: 113 tests, zero failures/errors/skips.
Only that disposable PostgreSQL volume/network was removed; external Maven cache
was retained. Live dev/qa/main data, credentials and service keys were unchanged.

## New coverage

- Three policy tests: inclusive 1..365-day horizons, outside-window and zero-stock
  rejection, expiry-day classification, leap calendar and invalid inputs.
- Eight actual JDBC tests using inventory_app: stable ID/first detection on replay;
  Bogotá midnight; resolution after an actual full-stock exit; horizon changes
  resolving/reopening the same record; no alert for pending-only stock; invalid
  identity/window/missing lot and regressed clock; transaction rollback; competing
  evaluators and an evaluator blocked until a stock exit commits.
- Alert evaluation itself preserved stock/movement/outbox evidence. The exit fixture
  intentionally changed its own stock and generated its normal movement/event.
- All existing core, JDBC, mocked HTTP, JWT, role and relay tests also passed.

CI runs Maven but skips environment-gated JDBC cases without INV_DB_URL. The
native acceptance above is operator-run. Catalog/Suppliers reference checks in
the entry fixture are stubbed; this is not multi-service HTTP or scheduler coverage.

The evaluator has no new REST route, batch scan, worker schedule or front connection.
Machine authorization, public alert reads, coherent live migration/deployment and
integrated QA remain pending. This does not close HU-WRK-001.
