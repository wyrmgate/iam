# DEV Deployment Topology

## Current status

The previously documented Railway backend is retired and is no longer an active Wyrmgate IAM deployment target.

No replacement managed server target is currently canonical. Do not infer a new provider from retained infrastructure files, CI examples, or prior deployment history. Selecting a replacement managed server/runtime is an implementation/operations decision and must be verified before this document names it as active.

The repository still preserves provider-neutral deployment contracts:

- `apps/server/Dockerfile` is the container build contract for `iam-server`;
- Spring Boot binds to `${PORT:8080}` and consumes database configuration through `IAM_DB_URL`, `IAM_DB_USER`, and `IAM_DB_PASSWORD`;
- `/actuator/health` is the server health endpoint;
- Flyway startup is verified by application integration tests;
- Core CI is the authoritative repository gate for compiling and testing the server.

## Console / Cloudflare Pages contract

The console build and Pages Function routing contract remain supported repository behavior:

- `apps/console` builds the static application;
- only `/api/*` is routed through `apps/console/functions/api/[[path]].js`;
- the Function reads server-side `IAM_BACKEND_ORIGIN`;
- browser code must not receive a `VITE_IAM_BACKEND_ORIGIN` secret/configuration substitute.

A Pages deployment is not a complete shared DEV environment unless `IAM_BACKEND_ORIGIN` points to a separately selected and verified IAM server environment. Until a replacement server target exists, do not describe Pages plus any database provider as an active end-to-end DEV topology.

Preview environments remain disabled unless they have explicit backend and data isolation. A feature-branch console must never silently target shared governed data.

## Server deployment contract

Any future managed server target must:

1. deploy a reviewed `main` revision whose required CI checks are green;
2. build the checked-in server artifact/container without provider-specific domain changes;
3. supply database credentials only through deployment secret/configuration facilities;
4. verify Flyway completed to the repository current migration version;
5. expose `/actuator/health`;
6. preserve external-call-outside-authoritative-transaction and retry/idempotency semantics;
7. document rollback and recovery behavior before being called the shared DEV baseline.

Provider-specific configuration belongs in a provider runbook/config file only while that provider is actually used. Obsolete provider configuration must be removed rather than kept as an apparent active contract.

## Database posture

PostgreSQL remains the implementation target. Neon-specific recovery material may be retained only where it describes an actually used database environment; it does not imply a canonical server provider or a complete active DEV topology.

For a selected DEV database, verify both application health and the latest successful Flyway version. A healthy HTTP endpoint alone is not migration evidence.

## Deployment ownership

GitHub workflows validate repository contracts; they do not create cloud infrastructure, mutate DNS, or materialize deployment secrets.

Core CI verifies the server. The DEV Deployment Contract currently validates the console/Pages repository contract and this topology status only. A new server-provider gate should be added only after that provider becomes an intentional current target.

## Retired Railway path

Railway Serverless, `apps/server/railway.json`, Railway Wait for CI, Railway deployment history, and Railway-specific serverless pool/sleep guidance are retired implementation details and are not current deployment requirements.

Historical commits retain the prior configuration if it is ever needed for reference. Do not restore it as a current contract without a new verified deployment decision.

See [`../operations/dev-managed-activation.md`](../operations/dev-managed-activation.md) for the current activation status.
