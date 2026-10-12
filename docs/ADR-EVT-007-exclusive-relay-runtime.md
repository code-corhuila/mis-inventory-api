# ADR-EVT-007: exclusive relay process

Status: accepted; deployment and live login provisioning pending.

The existing image can run a separate process with INVENTORY_RELAY_MODE=true.
Default false retains the Inventory API. Other mode values fail closed. Relay
mode does not start Spring's web/application context, stock adapters or endpoints.
It uses only JdbcOutbox and HttpEventPublisher with a two-connection pool.

Required settings: RELAY_DB_URL (PostgreSQL origin/database without embedded
credentials/query), RELAY_DB_PASSWORD_FILE, RELAY_CREDENTIALS_PATH (public.pem
and service.jwt), RELAY_KEY_ID, RELAY_ENVIRONMENT, RELAY_SERVICE_SUBJECT and
RELAY_WORKER_ORIGIN. The fixed DB username is inventory_relay_app, never the
API or admin login. Password comes from a bounded single-line file, not an env
password value; meaningful spaces are preserved. Public key/JWT validation runs
before claiming. Credentials and password files must be mounted read-only with
host permissions readable by UID 10001; no private key belongs in this process.
DB password is read at startup: restart the relay after an approved password
rotation. JWT renewal remains visible per delivery without process restart.

Startup validates named role attributes, capability membership, outbox read/
metadata update permissions and absence of stock/inbox/immutable-event access.
This is an operational guard, not protection against later administrator grants;
the operator must preserve reviewed permissions after startup. DB connect/socket/
statement waits and the publisher response wait are bounded. Polling uses one
attempt per second, no independent HTTP retry or transaction spanning publication.
Signals interrupt polling and allow up to 20 seconds to close; abandoned leases
still expire safely. Logs contain event/trace IDs and generic outcomes, not secrets.

The JWT is reread per delivery; expiry after startup leaves pending retries until
manual Security renewal. There is no automatic token issuance, root switch, HTTP
health endpoint or readiness guarantee in this PR. Liveness cannot prove delivery.

Native acceptance provisions a random-secret login only in the disposable test
database, exercises the full privilege guard, claim/retry, and actual denied
stock/payload/inbox/writer-role access. Unit tests cover opt-in, required settings,
file-secret handling and named administrative flags. This does not prove the
running Java process to real Worker/owner pipeline or close HU-EVT-001.
