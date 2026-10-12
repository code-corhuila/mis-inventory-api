# Atomic Inventory-Owned Event Effect

The protected POST /api/v1/internal/events/stock-changed requires a validated
mis-api JWT with INVENTORY role and signed service claim mis-worker. Human tokens,
Workflow and relay identities cannot call it by spoofing unsigned headers.
Machine credential provisioning and live JWT transport remain separate follow-ups.
Worker/relay identities cannot mutate entries or exits. Entry writes allow authorized
humans or Workflow; exit writes allow authorized humans only. Reads remain role-based.

Inventory verifies event structure and equality with its immutable source outbox.
It locks the existing lot (also serializing checkpoint creation), checks the inbox
and commits monotonic checkpoint plus immutable receipt/audit in one transaction.
Canonical sorted JSON hashes ignore property order. Replays return applied=false;
stale events are recorded but cannot regress the checkpoint. No stock delta is
applied again and no cross-domain table is read/written. The current effect is
receipt/checkpoint only; alert evaluation is added in the Worker alert stories.

Native JDBC tests cover replay after a discarded result, out-of-order events,
source conflict, rollback after writes and concurrent identical deliveries.
They use real PostgreSQL and TransactionTemplate, not HTTP Spring proxy wiring.
Method-security tests use synthetic JwtAuthenticationToken, not real Worker credentials.
No network loss/lease/retry or exactly-once delivery is claimed by these tests.
