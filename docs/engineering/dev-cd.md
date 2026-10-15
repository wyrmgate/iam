# DEV Deployment Topology

## Current status

Railway Serverless is the selected shared DEV compute target for `iam-server` again.

The intended managed DEV topology is Cloudflare Pages + Pages Functions, Railway Serverless, and Neon PostgreSQL. This is an implementation/operations choice for shared development and testing; it does not make Railway, Cloudflare, or Neon part of canonical IAM architecture and it does not settle OD-005 production topology.

The previous Railway service was retired and its repository-specific configuration was removed when that environment was taken down. The current reactivation is a new managed DEV deployment using the current Railway service/settings rather than an implicit rollback to the old service.

The repository preserves provider-neutral deployment contracts:

- `apps/server/Dockerfile` is the container build contract for `iam-server`;
- Spring Boot binds to `${PORT:8080}` and consumes database configuration through `IAM_DB_URL`, `IAM_DB_USER`, and `IAM_DB_PASSWORD`;
- `IAM_DEPLOYMENT_ENVIRONMENT=dev` identifies this environment as DEV;
- `/actuator/health` is the server health endpoint;
- Flyway startup is verified by application integration tests;
- Core CI, Security CI and Container CI remain the authoritative repository quality gates.

The Railway service is connected to `wyrmgate/iam` `main`, uses repository root as build context and `apps/server/Dockerfile`, enables Railway `Wait for CI`, has one shared DEV replica, enables Serverless scale-to-zero, and uses `/actuator/health` as the deployment health check. Railway remains deployment authority; GitHub Actions validates repository contracts and does not directly deploy or mutate Railway infrastructure.

The legacy Railway Config-as-Code file is not restored. Railway's former checked-in `apps/server/railway.json` path was retired, and the current service is configured through the selected Railway environment/settings. If Railway's supported infrastructure-as-code mechanism is adopted later, it requires a separate reviewed change.

## First-run bootstrap posture

A fresh shared DEV installation must use the ADR-0045 first-run web setup wizard. The ADR-0044 startup/headless bootstrap remains a supported product adapter for non-interactive deployments, but it is **not** the shared DEV activation path.

Railway supplies the installation-only setup proof as the secret variable `IAM_BOOTSTRAP_WEB_SETUP_SECRET`. The container entrypoint materializes that value into a mode-`0600` runtime file before Java starts, exports only `IAM_BOOTSTRAP_WEB_SETUP_SECRET_FILE` to the application process, and removes the plaintext environment variable from the Java child process. The Java setup adapter therefore preserves ADR-0045's file-only trusted boundary.

For shared DEV:

- `IAM_BOOTSTRAP_WEB_ENABLED=true`;
- `IAM_BOOTSTRAP_INSTALLATION_ENABLED=false`;
- `IAM_BOOTSTRAP_WEB_SETUP_SECRET` is a Railway secret containing at least 32 random characters;
- `IAM_BOOTSTRAP_INSTALLATION_CONSOLE_REDIRECT_URI` is the exact trusted Console callback URI;
- the Console is opened normally and the operator completes the first-run wizard;
- after `SEALED`, remove the setup secret and disable web setup.

The server container fails closed when `IAM_DEPLOYMENT_ENVIRONMENT=dev` and `IAM_BOOTSTRAP_INSTALLATION_ENABLED=true` are configured together. This prevents shared DEV from silently bypassing the user-visible first-run flow.

## Console / Cloudflare Pages contract

The console build and Pages Function routing contract remains:

- `apps/console` builds the static application;
- only `/api/*` is routed through `apps/console/functions/api/[[path]].js`;
- the Function reads server-side `IAM_BACKEND_ORIGIN`;
- browser code must not receive a `VITE_IAM_BACKEND_ORIGIN` secret/configuration substitute.

For the managed DEV topology, `IAM_BACKEND_ORIGIN` must point to the verified Railway DEV server origin. Do not point it at an unreviewed personal backend or any production system merely to make the console usable.

Preview environments remain disabled unless backend and data are isolated. A feature-branch console must never silently target shared governed DEV data.

## Railway server contract

The selected Railway DEV service must:

1. deploy only reviewed `main` revisions whose required GitHub checks are green;
2. use repository root `/` as Docker build context;
3. use `apps/server/Dockerfile` without a custom start command overriding its `ENTRYPOINT`;
4. expose the Railway-assigned `PORT`, with the application default remaining 8080;
5. use `/actuator/health` for deployment health validation;
6. obtain `IAM_DB_URL`, `IAM_DB_USER`, and `IAM_DB_PASSWORD` only from Railway secret/configuration storage;
7. set `IAM_DEPLOYMENT_ENVIRONMENT=dev`;
8. keep `IAM_OTEL_ENABLED=false` unless remote telemetry is intentionally being tested;
9. use Neon PostgreSQL as the shared DEV database target;
10. keep Serverless enabled while the environment is operated under the low-cost DEV posture;
11. use the first-run web setup wizard for fresh DEV installation and reject the startup/headless bootstrap adapter;
12. preserve external-call-outside-authoritative-transaction and retry/idempotency semantics.

The current free/low-cost Railway resource envelope is an operational constraint, not architecture. Resource limits may be tuned without changing canonical domain semantics, but the service must remain large enough to start Spring, run Flyway safely and pass the health check.

## Database posture

Neon PostgreSQL remains the managed DEV database target. Railway hosts the application server only; it must not silently introduce a second authoritative PostgreSQL service.

For every new or reset DEV database target, verify both application health and the latest successful Flyway version. A healthy HTTP endpoint alone is not migration evidence.

Before destructive DEV tests or risky migrations, follow [`../operations/backup-recovery.md`](../operations/backup-recovery.md) and verify the available Neon recovery path.

## Deployment ownership and CI

GitHub workflows validate repository contracts; they do not create Railway infrastructure, mutate DNS, or materialize deployment secrets.

The DEV Deployment Contract validates:

- the Cloudflare Pages build/proxy contract;
- the Railway-compatible server Docker/runtime configuration;
- the DEV wizard-bootstrap guardrail and runtime setup-secret materialization contract;
- the current managed DEV topology documentation.

Railway `Wait for CI` is the deployment gate after GitHub Actions. Railway then builds the checked-in Dockerfile, deploys the reviewed revision and applies its own `/actuator/health` gate.

The expected flow is:

```text
push/merge to main
  -> GitHub Core/Security/Container and DEV contract checks
  -> Railway Wait for CI releases the deployment
  -> Railway builds apps/server/Dockerfile
  -> container materializes the setup proof file when configured
  -> Flyway/application startup
  -> /actuator/health passes
  -> Console presents first-run wizard for an unsealed fresh database
```

## Activation status

Selecting Railway again does not by itself prove the shared DEV environment is operational. The current service must complete the checks in [`../operations/dev-managed-activation.md`](../operations/dev-managed-activation.md), including first-run wizard sealing, health, Flyway, tenant isolation, Cloudflare routing and recovery verification, before the managed DEV topology is described as fully activated.

Historical Railway configuration and deployment evidence remain historical implementation evidence only; they do not substitute for verification of the current service.
