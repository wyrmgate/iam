# DEV Deployment Topology

## Active DEV target

The first real DEV/testing/demo environment uses managed services:

- **Cloudflare Pages** serves the built `apps/console` static application.
- **Cloudflare Pages Functions** handles only `/api/*` and reverse-proxies those requests to the IAM server through the server-side `IAM_BACKEND_ORIGIN` binding.
- **Railway Serverless** runs `iam-server` from `apps/server/Dockerfile`.
- **Neon PostgreSQL** provides the DEV PostgreSQL database over TLS.
- **Grafana Cloud** is deferred until its free-tier behavior is confirmed suitable; application OTLP support remains disabled by default.

This is a DEV/demo implementation topology only. It does not redefine the canonical IAM capability architecture or establish a production topology.

## Console / Pages contract

Configure the Cloudflare Pages project with repository root directory `apps/console`, build command `npm run build`, and output directory `dist`. `apps/console/.node-version` pins the expected Node runtime for the build.

The console continues to call same-origin `/api/*`. `apps/console/functions/api/[[path]].js` forwards only that route family to `IAM_BACKEND_ORIGIN`. The binding is evaluated server-side by Pages Functions and must not be exposed as a `VITE_*` browser variable.

`apps/console/public/_routes.json` is copied into the Vite build output and includes only `/api/*`; normal HTML, JavaScript, CSS, images, and other static routes therefore do not invoke Functions unnecessarily.

For the shared DEV environment, set the Cloudflare Pages production branch to `main` and keep production-branch automatic deployment enabled. Preview branch deployments must remain disabled initially (`None`): feature-branch Pages previews do not have isolated Railway or Neon backends and must not accidentally target the shared DEV backend/database. Preview environments may be introduced later only with explicit backend/data isolation.

Local development is unchanged: Vite proxies `/api` and `/actuator` to `http://localhost:8080`.

## Railway / server contract

Railway builds the server from **repository root** with `apps/server/Dockerfile`. Do not set the Railway service Root Directory to `apps/server`: the Dockerfile intentionally uses repository-root paths such as `COPY apps/server/...`, so changing the build context to the service directory would break the image build.

`apps/server/railway.json` is the checked-in Railway service contract. It pins the Dockerfile path and `/actuator/health` health check while leaving the repository root as the build context. Because `.dockerignore` is also a repository-root Docker build input, it is included in Railway `watchPatterns` alongside `apps/server/**`.

The service must provide:

- `IAM_DB_URL` — a Neon JDBC URL using the direct, TLS-required endpoint because Flyway runs on the same Spring datasource at startup;
- `IAM_DB_USER`;
- `IAM_DB_PASSWORD`;
- `IAM_DEPLOYMENT_ENVIRONMENT=dev`;
- `IAM_OTEL_ENABLED=false` until observability is deliberately activated;
- `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0`;
- `SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT=30000` for the initial DEV serverless posture.

Railway supplies `PORT`; Spring Boot binds to `${PORT:8080}`, preserving port 8080 locally and in standalone containers.

Configure Railway's Git integration to deploy `main` and enable **Wait for CI**. Railway remains the deployment engine, but a `main` revision must not begin its provider deployment until the associated GitHub check suites have completed successfully.

Railway Serverless considers outbound traffic when deciding whether a service is idle, so long-lived database connections or telemetry can keep the service awake. The initial DEV posture therefore allows Hikari to drain to zero idle connections and leaves OTLP disabled. Revisit pool sizing if real DEV traffic patterns justify it.

Neon offers pooled endpoints for high-concurrency/serverless workloads, but Neon also recommends direct connections for migration tools. Because this application currently runs Flyway on the application datasource, the initial DEV contract uses the direct endpoint rather than introducing a second migration datasource prematurely.

## Deployment ownership

Cloudflare, Railway, and Neon deployment/configuration are external operator/provider actions. `.github/workflows/dev-cd.yml` validates the repository deployment contract but does not deploy, provision, mutate DNS, or materialize secrets.

Provider Git integrations deploy reviewed `main` revisions under the controls above. The managed DEV environment does not consume the GHCR release images as its deployment mechanism: Cloudflare Pages and Railway build from the reviewed repository revision. GHCR images and signed release manifests remain controlled release artifacts for staging/production and optional standalone-host/reference environments.

Rollback uses provider deployment history for application revisions; database down-migrations remain out of scope, so schema changes follow expand/contract compatibility.

## Superseded host-based DEV path

The former OCI/SSH/Caddy/Docker-Compose DEV deployment is no longer the active first DEV target. OCI/OpenTofu, Ansible, Caddy, and host Compose material are retained only as optional/reference infrastructure for a future standalone environment. They must not be described as the canonical current DEV activation path.

See [`../operations/dev-managed-activation.md`](../operations/dev-managed-activation.md) for the activation checklist.
