# Managed DEV Activation Runbook

## Purpose

Activate the first Wyrmgate IAM DEV/testing/demo environment on Cloudflare Pages, Railway Serverless, and Neon PostgreSQL without changing IAM domain architecture. This runbook contains no live credentials and performs no deployment by itself.

## 1. Select a reviewed revision

Use a full commit SHA from `main` whose required CI checks are green. Do not activate an unreviewed feature-branch revision as the shared DEV baseline.

## 2. Create Neon DEV database

Create a DEV-only Neon project/database and obtain a direct PostgreSQL connection string from Neon with TLS required. The application currently runs Flyway on its main datasource at startup, so use the direct Neon endpoint for the initial DEV contract rather than a pooled endpoint.

Convert the connection to JDBC form for `IAM_DB_URL` while retaining Neon TLS query parameters. Store `IAM_DB_URL`, `IAM_DB_USER`, and `IAM_DB_PASSWORD` only in Railway service configuration. Never commit them or reuse production data/credentials.

## 3. Configure Railway iam-server

Connect the repository to Railway and configure the server service to build with `apps/server/Dockerfile` from repository root.

Set deployment variables:

- `IAM_DB_URL`
- `IAM_DB_USER`
- `IAM_DB_PASSWORD`
- `IAM_DEPLOYMENT_ENVIRONMENT=dev`
- `IAM_OTEL_ENABLED=false`
- `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0`
- `SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT=30000`

Enable Railway Serverless for the DEV service. Do not set `PORT`; Railway supplies it. Verify the service starts, Flyway completes successfully, and `/actuator/health` is healthy over the Railway HTTPS service domain.

If the service does not sleep during idle periods, inspect outbound traffic first. Database connections and telemetry can keep Railway Serverless awake; do not weaken application correctness merely to force sleeping.

## 4. Configure Cloudflare Pages

Create a Pages project connected to the repository with:

- root directory: `apps/console`
- build command: `npm run build`
- build output: `dist`

Set the Pages Function server-side variable `IAM_BACKEND_ORIGIN` to the HTTPS Railway service origin, with no `/api` suffix and no credentials embedded in the URL.

Do not create a browser-exposed `VITE_IAM_BACKEND_ORIGIN`. Browser code remains same-origin.

## 5. Verify routing behavior

After provider deployments complete, verify:

1. the Pages root and static assets load normally;
2. `/api/system/info` succeeds through the Pages Function;
3. a static asset request does not execute the API Function path;
4. direct Railway `/actuator/health` remains healthy;
5. the browser does not need CORS access to the Railway origin because API traffic is same-origin through Pages;
6. no secrets or database values appear in Pages build output, browser JavaScript, repository files, or CI logs.

Cloudflare Pages routing must continue to use `_routes.json` with only `/api/*` included so static requests do not consume Pages Function invocations.

## 6. Custom domain and DNS

A custom DEV hostname is optional for initial activation. If used, configure it through Cloudflare Pages after the provider deployments are healthy. No repository workflow should change DNS.

## 7. Observability

Keep `IAM_OTEL_ENABLED=false` initially. Evaluate Grafana Cloud free-tier behavior separately before enabling remote telemetry. Any later observability activation must preserve the existing secret-redaction and data-minimization rules and should be checked for its effect on Railway Serverless sleeping.

## 8. Rollback and recovery

Use Cloudflare Pages and Railway deployment history to roll application revisions back to a previously green `main` SHA. Do not automatically down-migrate Neon. Database migrations must remain backward-compatible under expand/contract practices.

Before treating DEV as durable enough for meaningful testing, verify Neon backup/restore/recovery capabilities appropriate to the selected plan and document any plan-specific retention limits.

## 9. Completion criteria

DEV activation is complete only when the console loads from Pages, `/api/system/info` succeeds through the same-origin Function, Railway health is green, Neon connectivity is TLS-protected, no real secret exists in Git, and the selected revision is traceable to green `main` CI.

This topology is DEV/demo only and is not a production HA/DR decision.
