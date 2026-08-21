# Managed DEV Activation Runbook

## Purpose

Activate the first Wyrmgate IAM DEV/testing/demo environment on Cloudflare Pages, Railway Serverless, and Neon PostgreSQL without changing IAM domain architecture. This runbook contains no live credentials and performs no deployment by itself.

## 1. Select a reviewed revision

Use a full commit SHA from `main` whose required CI checks are green. Do not activate an unreviewed feature-branch revision as the shared DEV baseline.

## 2. Create Neon DEV database

Create a DEV-only Neon project/database and obtain a TLS-enabled PostgreSQL connection. Record the JDBC URL, username, and password only in the Railway service configuration. Never commit them.

Use a JDBC-form URL for `IAM_DB_URL` and retain TLS requirements from the Neon-provided connection parameters. Do not reuse production data or credentials.

## 3. Configure Railway iam-server

Connect the repository to Railway and configure the server service to build with `apps/server/Dockerfile` from repository root.

Set deployment variables:

- `IAM_DB_URL`
- `IAM_DB_USER`
- `IAM_DB_PASSWORD`
- `IAM_DEPLOYMENT_ENVIRONMENT=dev`
- `IAM_OTEL_ENABLED=false`
- `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0` for the initial serverless-friendly DEV pool posture

Do not set `PORT`; Railway supplies it. Verify the service starts, Flyway completes successfully, and the Railway service health endpoint `/actuator/health` is healthy over HTTPS.

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

## 6. Custom domain and DNS

A custom DEV hostname is optional for initial activation. If used, configure it through Cloudflare Pages after the provider deployments are healthy. No repository workflow should change DNS.

## 7. Observability

Keep `IAM_OTEL_ENABLED=false` initially. Evaluate Grafana Cloud free-tier behavior separately before enabling remote telemetry. Any later observability activation must preserve the existing secret-redaction and data-minimization rules.

## 8. Rollback and recovery

Use Cloudflare Pages and Railway deployment history to roll application revisions back to a previously green `main` SHA. Do not automatically down-migrate Neon. Database migrations must remain backward-compatible under expand/contract practices.

Before treating DEV as durable enough for meaningful testing, verify Neon backup/restore/recovery capabilities appropriate to the selected plan and document any plan-specific retention limits.

## 9. Completion criteria

DEV activation is complete only when the console loads from Pages, `/api/system/info` succeeds through the same-origin Function, Railway health is green, Neon connectivity is TLS-protected, no real secret exists in Git, and the selected revision is traceable to green `main` CI.

This topology is DEV/demo only and is not a production HA/DR decision.
