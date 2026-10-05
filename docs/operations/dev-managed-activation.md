# Managed DEV Activation Runbook

## Status

Railway Serverless has been selected again as the shared DEV compute target for `iam-server`, with Cloudflare Pages + Pages Functions for the console/edge and Neon PostgreSQL as the managed DEV database.

This is a new activation of the current Railway service, not restoration of the retired service instance. The activation is not complete until the verification steps in this runbook pass against the current reviewed `main` revision.

## Current selected topology

```text
Browser
  -> Cloudflare Pages static console
  -> /api/* Pages Function
  -> Railway Serverless iam-server
  -> Neon PostgreSQL
```

The topology is DEV only. It does not define or close OD-005 production HA/DR, SLO, RPO or RTO decisions.

## Railway service settings

The selected Railway service should use:

- repository: `wyrmgate/iam`;
- branch: `main`;
- repository root `/` as build context;
- Dockerfile builder with `/apps/server/Dockerfile`;
- no custom start command;
- generated/public Railway HTTPS domain;
- application port 8080 through Railway's assigned `PORT` contract;
- `/actuator/health` as Healthcheck Path;
- Railway `Wait for CI` enabled;
- one replica in the selected DEV region;
- Serverless enabled for the low-cost DEV posture;
- restart policy `On Failure`;
- no cron schedule for the application service;
- CDN caching and browser-challenge/Under-Attack behavior disabled for the API origin unless separately reviewed.

The old `apps/server/railway.json` file must not be recreated merely to mirror the retired deployment. The current Railway legacy Config-as-Code path is not the active repository contract.

## Required runtime configuration

Railway must provide these values through deployment configuration/secret storage, never source control:

- `IAM_DB_URL` — JDBC PostgreSQL URL for the selected Neon DEV database, including required TLS options;
- `IAM_DB_USER`;
- `IAM_DB_PASSWORD`;
- `IAM_DEPLOYMENT_ENVIRONMENT=dev`;
- `IAM_OTEL_ENABLED=false` for the baseline low-cost DEV posture.

Optional integration-event, SIEM, connector-worker, break-glass notification, signing and authentication settings remain disabled until the corresponding DEV test is intentionally activated.

Do not commit or paste real secrets into issues, PRs, documentation or ordinary logs.

## Activation procedure

1. Select a reviewed `main` SHA with required GitHub checks green.
2. Confirm Railway `Wait for CI` is enabled and the deployment is associated with that exact reviewed SHA.
3. Confirm Railway is building the checked-in `apps/server/Dockerfile` from repository root.
4. Confirm required Neon database variables are present in Railway secret/configuration storage.
5. Deploy and wait for Railway's `/actuator/health` gate to return 2xx.
6. Verify the deployed server reports healthy over the public Railway HTTPS origin.
7. Verify `public.flyway_schema_history` records the repository current migration version successfully; for the current v0.8 repository checkpoint this is Flyway V60.
8. Verify the server is using non-production DEV data and that tenant isolation checks pass.
9. Configure Cloudflare Pages `IAM_BACKEND_ORIGIN` to the verified Railway DEV origin.
10. Exercise the console `/api/*` path and confirm it reaches the intended DEV server rather than a stale/personal/production origin.
11. Verify the selected Neon recovery window and perform or record an isolation-safe recovery check before destructive migration/data tests.
12. Record non-secret activation evidence: deployed SHA, Railway environment/service identifier, health result, Flyway version, database branch/project identifier, console routing result and recovery-check result.

## Cloudflare Pages contract

The repository supports the existing Pages build/function contract:

- production branch may be `main` for the shared DEV console;
- root directory is `apps/console`;
- build command is `npm run build`;
- output directory is `dist`;
- only `/api/*` executes the Pages Function;
- `IAM_BACKEND_ORIGIN` is server-side only.

Do not expose the Railway origin as a browser-side `VITE_*` variable merely to bypass the Pages Function.

## Flyway verification

For a new or reset DEV database, verify both:

1. the deployed Railway server reports healthy;
2. `public.flyway_schema_history` records the repository latest migration successfully.

The application-startup regression test remains repository-level proof that Spring Boot and Flyway wiring works against an empty PostgreSQL database. It does not replace checking the actual managed DEV database.

## Serverless and background work

Railway Serverless is intentionally enabled for the low-cost DEV posture. Background outbound activity can keep the service awake, so remote telemetry and optional delivery workers stay disabled unless they are the subject of the test.

When testing long-running asynchronous delivery, connector workers, SIEM, webhooks, break-glass notifications or other background work, temporarily keeping the DEV service awake may be expected. Do not change domain semantics simply to optimize the provider's sleep behavior.

## Recovery

Neon provider-native recovery/history is the first DEV recovery path. Before destructive tests or risky migrations:

1. verify the intended recovery point is inside the current provider recovery window;
2. prefer an isolated/disposable recovery target when available;
3. verify schema and representative data after recovery;
4. remove disposable recovery objects after verification;
5. record the outcome as operational evidence.

Provider-native recovery is not an independently retained backup. Production recovery requirements remain governed by [`production-readiness.md`](production-readiness.md) and [`production-recovery-drill.md`](production-recovery-drill.md).

## Completion criteria

Managed DEV activation is complete only when all of the following are true:

- the current Railway service deploys a reviewed green `main` SHA;
- Railway `/actuator/health` is healthy;
- Flyway is confirmed at the repository current migration version;
- the service uses the intended Neon DEV database and non-production data;
- tenant isolation verification passes;
- Cloudflare Pages `/api/*` routes to the intended Railway DEV origin;
- the Neon recovery path has been verified for the current environment;
- no real secret exists in Git or ordinary logs;
- repository DEV topology documentation matches the deployed environment.

Until all of these are recorded, Railway is the **selected managed DEV target**, but the shared managed DEV environment remains **activation pending**.
