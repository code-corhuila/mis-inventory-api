# ADR-WRK-003: Owner Expiry HTTP Boundary

Status: accepted for the API increment; HU-WRK-001 remains open.

## Decision

POST /api/v1/internal/alerts/expiry/evaluate requires INVENTORY and service=mis-worker.
Its exact body is lotId (canonical UUID) and windowDays (integer 1..365).
Inventory supplies physical balance, expiry and Bogota evaluation date under its
existing transaction/lot lock. Caller snapshots and human evaluation are rejected.
Repeated calls refresh current evidence, not an immutable Idempotency-Key response.
alertPresent means a record exists, including RESOLVED; never-eligible lots return
false/null. No stock change, deletion or human acknowledgement is introduced.

GET /api/v1/alerts/expiry requires a human ADMIN, INVENTORY or SUPERVISOR without
a service claim. CONSULTATION and machine identities cannot read this endpoint.
Filters page/limit/lotId/state/severity combine with AND; unknown, repeated and
empty values fail. Ordering is expiresOn/lotId ascending. Count and page share a
read-only repeatable-read transaction, but separate requests are not one snapshot.
Rows are persisted evaluations: evaluatedOn/lastEvaluatedAt express freshness.
An empty list does not prove a Worker scan ran or no risky stock exists.

## Deployment And Evidence

All seven Inventory DB changesets are required. Health checks nine owned tables;
the older active dev database must be aligned before replacing its API container.
This publication does not migrate dev or route alerts through Gateway. Worker
triggering/scheduling, signed multi-service HTTP acceptance, frontend and QA follow.
Native Maven acceptance uses restricted inventory_app and disposable PostgreSQL.
HTTP mapping tests mock ports and principals; method-security tests use real Spring
proxies with synthetic JWT objects, not signed HTTP tokens. Contract fixtures check
OPEN/RESOLVED and null/presence envelopes; they are not captured live HTTP evidence.
Ordinary CI skips native JDBC tests when INV_DB_URL is absent.
