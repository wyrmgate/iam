# Managed DEV Activation Runbook

## Purpose

Activate the first Wyrmgate IAM DEV/testing/demo environment on Cloudflare Pages, Railway Serverless, and Neon PostgreSQL without changing IAM domain architecture. This runbook contains no live credentials and performs no deployment by itself.

## 1. Select a reviewed revision

Use a full commit SHA from `main` whose required CI checks are green. Do not activate an unreviewed feature-branch revision as the shared DEV baseline.

## 2. Create Neon DEV database

Create a DEV-only Neon project/database and obtain a direct PostgreSQL connection string from Neon with TLS required. The application currently runs Flyway on its main datasource at startup, so use the direct Neon endpoint for the initial DEV contract rather than a pooled endpoint.

Convert the connection to JDBC form for `IAM_DB_URL` while retaining Neon TLS query parameters. Store `IAM_DB_URL`, `IAM_DB_USER`, and `IAM_DB_PASSWORD` only in Railway service configuration. Never commit them or reuse production data/credentials.

## 3. Configure Railway iam-server

Connect the repository to Railway and configure the server service to build with `apps/server/Dockerfile` from **repository root**. Leave Railway Root Directory unset (repository root). Do not set it to `apps/server`, because the Dockerfile uses repository-root `COPY apps/server/...` paths.

Use `apps/server/railway.json` as the checked-in service contract. It pins the Dockerfile path, `/actuator/health` deployment health check, and change detection for both `apps/server/**` and the repository-root `.dockerignore` build input.

Configure the Git source to deploy `main`, keep automatic deployment enabled, and enable **Wait for CI** so Railway does not begin deployment until the commit's GitHub check suites have completed successfully.

Set deployment variables:

- `IAM_DB_URL`
- `IAM_DB_USER`
- `IAM_DB_PASSWORD`
- `IAM_DEPLOYMENT_ENVIRONMENT=dev`
- `IAM_OTEL_ENABLED=false`
- `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0`
- `SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT=30000`
- `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5`

Enable Railway Serverless for the DEV service. Do not set `PORT`; Railway supplies it. Verify the service starts, Flyway completes successfully, and `/actuator/health` is healthy over the Railway HTTPS service domain.

The maximum pool size of `5` is part of the validated initial DEV posture. During first activation an effective pool size of `2` caused Flyway startup to time out. Keep `5` unless measured DEV behavior justifies a deliberate change. This is not production sizing guidance.

If the service does not sleep during idle periods, inspect outbound traffic first. Database connections and telemetry can keep Railway Serverless awake; do not weaken application correctness merely to force sleeping.

### Flyway startup verification

For the current Spring Boot 4.1.1 server, Flyway auto-configuration depends on `spring-boot-starter-flyway` plus the PostgreSQL Flyway database module. A direct `flyway-core` dependency alone was insufficient during first activation: the server could start and report healthy while the empty Neon database remained unmigrated.

The repository now contains an application-startup regression test that boots the real application against an empty PostgreSQL database and verifies `flyway_schema_history`, the current migration version, and the capability schemas. Keep that test green whenever Spring Boot or Flyway dependencies change.

For a new or reset managed DEV database, verify both:

1. Railway `/actuator/health` is `UP`;
2. the latest successful version in `public.flyway_schema_history` matches the repository's current migration set.

A green HTTP health check by itself is not migration evidence.

## 4. Configure Cloudflare Pages

Create the IAM console Pages project connected to the repository with:

- production branch: `main`
- automatic production deployment: enabled
- Preview branch deployments: `None` / disabled initially
- root directory: `apps/console`
- build command: `npm run build`
- build output: `dist`

Preview deployments stay disabled until a future design provides isolated backend and database state for feature-branch previews. A feature-branch console must not silently use the shared Railway/Neon DEV backend.

Set the Pages Function server-side variable `IAM_BACKEND_ORIGIN` to the HTTPS Railway service origin, with no `/api` suffix and no credentials embedded in the URL.

Do not create a browser-exposed `VITE_IAM_BACKEND_ORIGIN`. Browser code remains same-origin.

The IAM console Pages project is distinct from any Pages project used to publish curated public documentation. Do not configure the docs publication workflow to deploy into this application project.

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

The active managed DEV database uses Neon provider-native recovery rather than the standalone-host backup timer. Neon supports point-in-time restore within the project's configured restore window, and current restore-window limits depend on the Neon plan. As of the current provider documentation, the Free plan supports Instant Restore up to 6 hours or 1 GB of changes (whichever is smaller), Launch can configure up to 7 days, and Scale up to 30 days. Provider limits can change, so the Neon console for the actual project remains the source of truth for the selected plan and configured window.

Before declaring a newly created DEV project recoverable:

1. record the active Neon plan and configured restore window in the operator's environment inventory, not in application secrets;
2. confirm the Backup & Restore / restore-history controls are available for the project;
3. create a harmless test row or schema object, wait long enough to establish a distinct recovery point, and verify a point-in-time recovery can be created/restored without overwriting the active branch unexpectedly;
4. remove the disposable recovery branch/object after verification;
5. repeat a recovery drill after major provider-plan changes or before destructive migration testing.

For additional logical portability or longer retention than the selected Neon plan provides, use an independent `pg_dump`/`pg_restore` process to external protected storage. Do not assume provider PITR is a substitute for every future production backup requirement.

See [`backup-recovery.md`](backup-recovery.md) for the managed-DEV versus standalone-host recovery boundary.

## 9. Completion criteria

DEV activation is complete only when the console loads from Pages, `/api/system/info` succeeds through the same-origin Function, Railway health is green, Neon connectivity is TLS-protected, Railway `Wait for CI` is enabled, Cloudflare preview branch deployments are disabled, no real secret exists in Git, the selected revision is traceable to green `main` CI, Flyway startup is proven against the target database, and the active Neon recovery window has been verified for the selected plan.

This topology is DEV/demo only and is not a production HA/DR decision.
