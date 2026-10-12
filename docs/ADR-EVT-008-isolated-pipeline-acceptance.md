# ADR-EVT-008: isolated signed pipeline acceptance

Status: accepted; live environment publication/configuration remains separate.

compose.relay.yml adds a separate UID 10001 process on platform, no ports, a
read-only root, dropped capabilities and no-new-privileges. Its password file and
credential directory are required read-only mounts with create_host_path=false.
The runtime image is shared with Inventory, not its API process/login. Deployment
requires an operator-provisioned inventory_relay_app login granted only the relay
capability; production provisioning and file permissions are not performed here.

Test-EventPipeline requires InfraRoot, InventoryDbRoot, WorkerRoot and SecurityRoot.
It uses a unique disposable PostgreSQL/network project with six migrations and
real Inventory, Worker and relay processes. Security's versioned issuer is copied
into a synthetic fixture and signs registered distinct machine identities there.
Only public keys/JWTs are mounted into consumers, never Security's private key.
Fixtures use normal application SQL permissions to create a movement/outbox event;
this does not test human creation APIs or cross-domain reference consistency.

A test-only proxy forwards a real Worker response, then replaces its first 200
with 503 after the owner receipt committed. The real relay retries, receives the
existing receipt and delivers once. PostgreSQL verifies one inbox receipt, Worker
actor identity, preserved stock and multiple attempts. A second event remains
pending while Worker is stopped, then delivers after Worker/relay process restart.
This tests graceful container restart, not SIGKILL at every transaction boundary.

Catalog/Suppliers health and Security's JWKS endpoint are stubs, not deployed
business services; issuance still uses Security's real operator script. This is
transport/persistence acceptance with synthetic data, not full release QA. CI
parses the script; the native multi-repository acceptance is run locally. Cleanup
removes only the uniquely named fixture project, not mis-dev/qa/main or their data.

No live credential issuance, production schema upgrade, root switch or teacher
approval is implied. Existing OPS/security migration publication dependencies
remain explicit; HU-EVT-001 closure must distinguish this evidence from live QA.
