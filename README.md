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

This first publication intentionally registers only the core Maven module.
Persistence adapters, authentication, HTTP endpoints and runnable deployment
are published next; this commit alone is not a running inventory service.
No database credentials, application keys or environment files are included.
