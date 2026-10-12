# HU-WRK-001: Signed Expiry Pipeline Acceptance

Scope: real PostgreSQL, Inventory API, Worker, relay and Nginx Gateway. Baselines:
Inventory develop cebf49f, DB 7e49046, Worker ac47e00, Gateway e300c28; Security
issuer source 4e67155. The run exposed a resolved-alert reevaluation bug: PostgreSQL
checks the proposed INSERT before ON CONFLICT updates the old row. A proposed first
detection of now with an earlier preserved resolution failed the temporal CHECK.
The candidate now uses the original firstDetectedAt when a record exists; identity,
resolution and update-column ownership remain unchanged. Native regression uses
a later clock, not the fixed-time repeat that missed this failure.

## Reproduction

Run tests/Test-EventPipeline.ps1 with InfraRoot, InventoryDbRoot, WorkerRoot,
SecurityRoot, -ExpiryAlerts and GatewayRoot. Requires PowerShell 7 and Linux Docker;
InfraRoot supplies its ignored dev.env for disposable database configuration.
The unique project/volume is validated before operations. Model/secrets remain in
memory. Ephemeral credentials are isolated in a temporary fixture; runtime Worker,
relay and probe receive only the public/token or password files they need.
Neither private signing keys nor existing service identities are given to consumers.
Finally removes only the owned temporary project, volume/network and fixture.

## Exercised Cases

- Entry intent delivered through signed relay/Worker/owner HTTP; the proxy loses
  one successful ACK. Retry leaves one inbox record, stable alert and unchanged stock.
- Worker outage creates a pending intent; restart delivers it without duplicate effect.
- An expired historical lot is found by startup scan; another inserted after that
  scan is found on the next 60-second interval without an inbox event for either.
- Historical seeds create lot, entry, movement and already-delivered outbox in one
  administrative fixture transaction. Other seeds use inventory_app. This bypasses
  entry validation intentionally for history, never a route for live stock changes.
- A real human INVENTORY HTTP exit removes ten units. Keyed replay returns its
  original snapshot; evaluation resolves the same alert ID, retaining two movements.
- Signed ADMIN/INVENTORY/SUPERVISOR reads through Gateway pass actual response
  schemas, including an empty second page with preserved total. CONSULTATION and
  Worker public reads are denied. Human evaluation is denied by Inventory; the
  internal route is unavailable through Gateway but usable by the signed Worker.

## Limits

2026-10-12: native Maven verification passed 121 tests, zero failures/errors/skips,
in mis-inv-api-check-429288fc. Signed expiry acceptance passed in
mis-evt-check-c69f647f2ef0, including real filtered read schemas/CORS and reevaluation
of the resolved alert preserving its first-detection/resolution timestamps.
Both owned projects, volumes/networks and temporary fixtures were removed.
The previous probe reproduced HTTP 503 before the fix; its fixture was removed too.

Human tokens are signed test fixtures, not real Security login. JWKS and reference
services are stubs; service tokens use the actual Security issuer with synthetic
keys. Front rendering is verified separately, not by this container probe. This
does not exercise civil-midnight change itself, full domain references, continuous
pagination churn, every failure path, credential rotation or QA/main promotion.
The active dev 404 is not fixed by running this test. Live backup/schema/container
alignment and full configured-identity/browser acceptance are still required.
