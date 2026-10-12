# mis-inventory-api

El nucleo de relay reserva eventos con lease y confirmacion protegida por token;
ver docs/ADR-EVT-005-fenced-outbox-relay.md. No se activa automaticamente ni
se despliega todavia. Las pruebas JDBC usan PostgreSQL desechable, no el entorno dev.
El publicador HTTP con credencial propia esta implementado por separado en
docs/ADR-EVT-006-signed-relay-http.md; falta conectarlo al login y despliegue exclusivos.
El modo de proceso separado se documenta en docs/ADR-EVT-007-exclusive-relay-runtime.md.
Esta desactivado por defecto; todavia no hay un relay desplegado en dev.
La composicion separada y aceptacion desechable del circuito firmado estan en
docs/ADR-EVT-008-isolated-pipeline-acceptance.md. No cambian el entorno activo.

El contrato productor de eventos vive en `docs/stock-changed.schema.json`.
ADR-EVT-001 documenta el outbox existente; ADR-EVT-002 define la frontera prevista
del relay y consumidor. Contrato publicado no significa entrega implementada.

La operacion interna de eventos valida el origen y registra inbox/checkpoint en una
transaccion propia, sin volver a descontar/sumar stock. Requiere identidad JWT
mis-worker y rol INVENTORY; tokens humanos no bastan. Consulte ADR-EVT-004.
Requiere la quinta migracion de mis-inventory-db. Pruebas JDBC y autorizacion por
metodo estan incluidas; transporte, credenciales Worker y relay siguen pendientes.

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
publications add persistence adapters and the HTTP application. Container deployment
and real integration acceptance follow separately.
No database credentials, application keys or environment files are included.

## Entry Persistence

`inventory-adapters` now includes JDBC preparation, activation and cancellation.
Request snapshots and stock changes share the transaction; activation inserts a
movement whose database trigger emits the outbox event. Active stock cannot be
cancelled. Cross-domain references are checked through HTTP, never shared SQL.
The HTTP application is now published; container deployment follows separately.

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
stock additionally declares CONSULTATION. Application security now enforces these
annotations, alongside strict JSON and correlation/request-size filters.
Standalone MockMvc tests use synthetic principals and mocked ports, not JWT
signature validation, request filtering or role enforcement. No runnable application
or deployment is introduced in this controller publication.

## Application Security

`inventory-app` now starts the Spring server with stateless RS256 bearer validation,
issuer/audience/subject/expiry/role checks, method security and correlated 401/403.
Required environment variables supply the database password, JWKS and reference URLs;
the application connects only as inventory_app. `/health` remains public.
JSON rejects unknown fields and scalar coercion; POST bodies are limited to 64 KiB.
Tests use ephemeral RSA keys, method-security proxies with synthetic authentication,
and servlet mocks. They do not prove the live JWKS/filter/upstream integration.
Container deployment and real HTTP acceptance are described below.

## Container And Contract

`deploy/Dockerfile` verifies Maven modules and runs the jar as UID 10001.
`deploy/compose.yml` joins the shared platform without publishing host ports.
OpenAPI 3.1 is validated in CI. `tests/Test-Access.ps1` takes BaseUrl, SecurityUrl
and the ignored SecurityEnv path; it checks real login/JWKS, correlation and roles
without creating data. Full write/replay integration acceptance is described below.

## Real Write Acceptance

Build the schema validator: `docker build -t mis-inventory-contracts -f tests/Dockerfile.contracts .`.
Run `tests/Test-Integration.ps1` with BaseUrl (Inventory), GatewayUrl and SecurityEnv.
It creates a unique active medication/supplier, one lot, an activated entry, a
cancelled entry and one two-unit exit. Fixtures remain as audit evidence: no deletion
or mutation of existing records. The final balance is eight with two movements.
The script checks real JWT/roles, HTTP reference lookups, strict JSON, conflict and
snapshot replays, cancellation, insufficient stock and schemas of actual responses.
It does not cover concurrent HTTP writes, live expiry changes, restart recovery,
every failure path or the saga; JDBC race/rollback tests remain separate.
CI validates syntax/schema/unit tests; multi-service acceptance is a separate run.

`tests/Test-Publication.ps1` builds the images, starts an additional UID-10001
Inventory container on a random loopback port, runs access/write acceptance and
removes only that container. Supply InfraEnv, SecurityEnv, GatewayUrl and DockerNetwork.
The supplied network must provide postgres/security/catalog/suppliers aliases with
Inventory migrations already applied. Existing domain data is not erased; new audit
fixtures are retained. No secret values are passed as command-line literals.

## Owner Expiry Evaluation

ExpiryAlerts/ExpiryPolicy and JdbcExpiryAlerts classify and persist one owned lot
under a row lock, using owner time in America/Bogota and a 1..365-day horizon.
Physical units, expiry and date come from the owner, not a caller-provided snapshot.
Replay keeps stable alert identity/first detection; ineligible alerts resolve without
deletion, and renewed eligibility reopens the same record. Stock is never changed
by evaluation. Regressed evaluation time is rejected rather than overwriting evidence.
Requires the seventh Inventory DB changeset; no live migration is performed here.
See [decision](docs/ADR-WRK-002-owner-expiry-evaluation.md) and
[native acceptance](docs/WRK-001-evaluation-acceptance.md).

This is an internal application port, not a new HTTP endpoint. Machine authorization,
worker triggering/scheduling, public alert reads, frontend integration and QA remain
pending. HU-WRK-001 is not closed and the low-stock flag is unchanged.
