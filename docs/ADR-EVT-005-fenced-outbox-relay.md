# ADR-EVT-005: fenced outbox reservations and retry core

Status: accepted; HTTP publisher, scheduler and restricted relay login pending.

Inventory owns delivery metadata in its existing outbox. JdbcOutbox claims one
due PENDING event using FOR UPDATE SKIP LOCKED in a short independent transaction.
It increments attempts (saturating at the integer limit), generates a unique
lease token and reserves for 30 seconds using PostgreSQL's clock. It returns
the immutable validated event only after commit. An enclosing transaction is
rejected so the later publisher cannot accidentally hold it across network I/O.

EventRelay publishes through a port and accepts only a matching event ID,
sequence and accepted=true. Delivery/retry updates require the same unexpired
lease and PENDING status. Expired or replaced owners cannot modify metadata.
Only confirmed delivery writes DELIVERED/delivered_at; retries clear the lease
and defer next_attempt_at with 1,2,4,...,256,300 second bounded backoff. Attempts
are not deleted after an arbitrary retry limit; monitoring/operational recovery
will be needed for persistent failures. Database write errors propagate.

Process loss leaves a lease to expire. Owner commit followed by lost response
leaves PENDING; the next delivery obtains the existing owner receipt and can
complete without reapplying the effect. This is at-least-once transport, not
exactly-once network delivery. No stock, event payload or audit movement changes.

Unit tests exercise acknowledgement matching, idle behavior, lease loss and
backoff. Native PostgreSQL tests use inventory_app in the disposable fixture:
concurrent reservation, expiry/reclaim, stale-owner fencing, delayed retry,
immutable payload and a simulated lost response after a real committed owner
receipt. Publisher callbacks assert there is no surrounding DB transaction.
Reconstruction of a relay object is tested, not an OS process crash or HTTP loss.

These classes are not registered as a scheduled live relay. This PR does not
publish messages to Worker, create machine credentials or change deployment.
The dedicated least-privilege relay database login must precede live activation;
inventory_app is used here only as the existing native test fixture. HU-EVT-001
remains open, including signed HTTP integration and restart/timeout acceptance.
