# HU-WRK-001 HTTP Increment Acceptance

Date: 2026-10-12. Baseline: Inventory API develop 056f77b; DB develop 7e49046.

Test-Persistence.ps1 applied seven changesets in disposable project
mis-inv-api-check-9f05799d and ran Maven verify with Java 17:
42 core + 60 adapters + 18 app = 120 tests; zero failures/errors/skips.
Native checks exercise restricted SQL filters, pagination totals, OPEN/RESOLVED
mapping and repeatable-read transactions. Existing owner race/rollback tests pass.
MockMvc checks exact evaluation body/types, empty result, page envelope and hidden
dependency details with trace. Spring proxies test all four roles against human,
Worker, Workflow and relay claims. These JWT objects are synthetic, not signed HTTP.
OpenAPI Docker build and test_expiry_contract.py passed; contract samples are fixtures.
The random project and volume were removed; live dev/schema/credentials were unchanged.
Deployment alignment, Gateway route, Worker scan, real signed HTTP, frontend and QA
are not demonstrated by this run. HU-WRK-001 remains open.
