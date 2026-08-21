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

Local development is unchanged: Vite proxies `/api` and `/actuator` to `http://localhost:8080`.

## Railway / server contract

Railway builds from the repository with `apps/server/Dockerfile`. The service must provide:

- `IAM_DB_URL` — Neon JDBC URL, with TLS required by the selected Neon connection string;
- `IAM_DB_USER`;
- `IAM_DB_PASSWORD`;
- `IAM_DEPLOYMENT_ENVIRONMENT=dev`;
- `IAM_OTEL_ENABLED=false` until observability is deliberately activated.

Railway supplies `PORT`; Spring Boot binds to `${PORT:8080}`, preserving port 8080 locally and in standalone containers.

For serverless-friendly DEV operation, configure the datasource pool conservatively (for example `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0`) and keep migrations backward-compatible. Flyway still owns forward schema migration at server startup.

## Deployment ownership

Cloudflare, Railway, and Neon deployment/configuration are external operator/provider actions. `.github/workflows/dev-cd.yml` validates the repository deployment contract but does not deploy, provision, mutate DNS, or materialize secrets.

Provider Git integrations should deploy reviewed `main` revisions. Rollback uses provider deployment history for application revisions; database down-migrations remain out of scope, so schema changes follow expand/contract compatibility.

## Superseded host-based DEV path

The former OCI/SSH/Caddy/Docker-Compose DEV deployment is no longer the active first DEV target. OCI/OpenTofu, Ansible, Caddy, and host Compose material are retained only as optional/reference infrastructure for a future standalone environment. They must not be described as the canonical current DEV activation path.

See [`../operations/dev-managed-activation.md`](../operations/dev-managed-activation.md) for the activation checklist.
