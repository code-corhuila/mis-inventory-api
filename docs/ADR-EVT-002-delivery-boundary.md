# Stock Event Delivery Boundary

Decision: Inventory owns the outbox, consumer inbox and any alert/checkpoint effects.
Worker receives events over internal HTTP and requests effects from Inventory over
its authenticated API. Worker does not read/write another domain's tables and does
not own a stock database. No new database engine or message broker is introduced.

Planned relay: claim pending Inventory outbox rows with a bounded lease and fencing
token, release the database transaction before HTTP, and mark delivered only after
Worker acknowledges the exact event. Timeout/lost ACK leaves the event retryable.
Retry/backoff and restart recovery must be tested before declaring delivery complete.

Planned consumer: validate the producer schema and signed machine identity, then ask
Inventory to atomically deduplicate eventId with its effect/checkpoint. Apply only
newer per-lot sequences; duplicates and stale events may be acknowledged after the
owner confirms durable handling. Never compute stock by summing redelivered deltas.

Identity issuance stays in Security. Proposed identities use separate subjects:
the Inventory relay publishes to an event-specific audience; Worker calls the owner
using its own API identity. Human JWTs and unsigned service headers are not enough.
Private signing keys must not be copied to Worker or Inventory relay containers.

This PR publishes only the existing v1 schema and the intended boundary. It does not
implement HTTP transport, machine credential provisioning, inbox, leases or alerts.
There is no exactly-once network delivery claim and no historical-event backfill.
