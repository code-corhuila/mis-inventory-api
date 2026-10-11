# mis-inventory-api

> inventory bounded context: service API

Part of the **Medicine Inventory System** distributed system — team `medicine-inventory-system`, Grupo 1.
Governance and documentation live in [`mis-docs`](https://github.com/code-corhuila/mis-docs).

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `mis-docs`.

## Inventory Core Publication

`inventory-core` is framework-independent Java 17. It defines entry/exit requests,
read/write ports, stock and movement DTOs, bounded pagination and filters.
Lot numbers are normalized; identifiers, request keys and correlation IDs are
validated. Entry freshness uses the civil date in America/Bogota.
Unit tests cover input boundaries and calendar rules, not database concurrency.

```sh
mvn -B verify
```

The first publication registered only the core Maven module; the subsequent
publications add persistence adapters. Authentication, HTTP endpoints and runnable
deployment follow next; these modules alone are not a running inventory service.
No database credentials, application keys or environment files are included.

## Entry Persistence

`inventory-adapters` now includes JDBC preparation, activation and cancellation.
Request snapshots and stock changes share the transaction; activation inserts a
movement whose database trigger emits the outbox event. Active stock cannot be
cancelled. Cross-domain references are checked through HTTP, never shared SQL.
The runnable HTTP application is not published yet.

```powershell
./tests/Test-Persistence.ps1 -InfraRoot 'D:\descargas\pago 2\mis-infra-postgres' -InventoryDbRoot 'D:\descargas\pago 2\Julian-Alberto-Trujillo-Bonilla-\tmp\repos-oficiales\mis-inventory-db'
```

Requires PowerShell 7, Docker Compose include and infrastructure's ignored dev.env.
The script migrates a random isolated PostgreSQL project and tests JDBC as
inventory_app. It removes only its own containers and database volume.
Database tests use real transactions with TransactionTemplate and stub reference
validation; they also cover two concurrent activations of one entry and rollback
after all activation writes, but do not verify Spring HTTP wiring or real
Catalog/Suppliers authentication. Separate HTTP-stub tests cover reference responses.
Ordinary Maven/CI runs skip the database tests when INV_DB_URL is absent.

## Stock Queries And Exits

JDBC stock queries separate pending, on-hand and available quantities; expired
lots retain on-hand history but expose zero available stock. Read pagination uses
repeatable-read transactions and movements filter dates in America/Bogota.
Exits lock their lot, reject expired/insufficient stock, and persist the discount,
audit, movement, outbox event and idempotency snapshot in one transaction.
Native acceptance additionally covers exit replay/conflict, rollback after writes,
two competing exits, expiry midnight, stock filters and movement pagination.
The tests use restricted inventory_app and TransactionTemplate, not HTTP wiring.
No relay or consumer is introduced by these storage adapters.

## HTTP Controllers

Controllers publish entries, activate/cancel actions, exits, stock, movements and
health routes. New writes return 201 and a Location; replays return 200. Failures
use the correlated error envelope and do not expose database exception details.
Transition bodies must be absent or empty objects. Health checks owned tables.
Write methods declare ADMIN/INVENTORY; history declares ADMIN/INVENTORY/SUPERVISOR;
stock additionally declares CONSULTATION. Actual enforcement needs the application
security configuration, which is published next along with strict JSON and filters.
Standalone MockMvc tests use synthetic principals and mocked ports, not JWT
signature validation, request filtering or role enforcement. No runnable application
or deployment is introduced in this controller publication.
