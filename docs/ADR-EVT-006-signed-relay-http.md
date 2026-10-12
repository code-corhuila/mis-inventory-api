# ADR-EVT-006: signed relay HTTP publisher

Status: accepted; runtime wiring and exclusive database login pending.

HttpEventPublisher implements the relay publication port without a scheduler or
database access. It calls a fixed Worker origin at /internal/events/stock-changed,
forwarding the immutable event and its trace ID. It never forwards a human token.
It reads the relay token file afresh per attempt and validates RSA signature,
configured key ID/subject/environment, mis-security issuer, exact mis-events
audience, mis-inventory-relay service, no roles, integer dates, canonical jti and
60..3600 second lifetime. Expired/future-issued or foreign credentials do not send.
Only a provisioned public RSA key is accepted; no private key is used here.

HTTP uses no redirects or proxies, a one-second connection timeout and a
six-second full-response deadline, including a stalled body. The bounded body
subscriber cancels after 64 KiB or timeout; the token read is capped at 8 KiB.
Only status 200 with exactly eventId/sequence/accepted, matching canonical event
ID and integer sequence, and accepted=true confirms delivery. Duplicate JSON
fields, coercion, extra fields or mismatches are rejected. Exceptions are generic
and do not include credentials/payloads. Interrupted calls preserve interruption.

EventRelay turns failed publication into fenced pending retries; the publisher
does not independently retry or mark delivery. Worker still confirms only after
Inventory's atomic owner receipt. This remains at-least-once transport. HTTP is
limited to the local private platform network; external deployment requires TLS.

Tests use a real loopback HTTP server and synthetic RSA signatures. They verify
sent body/trace/identity, atomic token renewal, invalid credential refusal before
HTTP, redirects/non-200, bad/mismatched/large receipts and stalled body timeout.
The HTTP server is a Worker stub, not the deployed Worker or a native database.
This does not activate a live relay, provision its database login, establish full
pipeline restart acceptance, or close HU-EVT-001.
