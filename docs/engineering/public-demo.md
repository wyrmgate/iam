# Public DEMO Environment

## Purpose

Sprint 10 establishes a separately isolated DEMO deployment path for showing Wyrmgate IAM without coupling demo operations to DEV.

DEMO is a promotion target, not a build target. It reuses immutable `sha-<git-sha>` server and console images that were already published from `main`.

## Isolation

DEMO uses its own host deployment root and Docker resources:

- root: `/opt/wyrmgate/iam-demo`
- Compose project: `wyrmgate-iam-demo`
- internal network: `wyrmgate-iam-demo-internal`
- edge network: `wyrmgate-demo-edge`
- PostgreSQL volume: `wyrmgate-iam-demo-postgres-data`
- Caddy data volume: `wyrmgate-demo-caddy-data`
- Caddy config volume: `wyrmgate-demo-caddy-config`

This permits DEV and DEMO to evolve independently when they are hosted separately. A single machine cannot bind two independent edge stacks to ports 80/443 at the same time; shared-host multi-environment routing is intentionally outside this baseline.

## Promotion

The `DEMO` GitHub Actions workflow is manual for deployment. Supply a full 40-character commit SHA corresponding to an already-published `main` image.

The workflow:

1. materializes host-only configuration from GitHub Environment secrets;
2. copies an immutable release bundle to `/opt/wyrmgate/iam-demo/releases/<sha>`;
3. authenticates the host to GHCR with the job-scoped GitHub token;
4. pulls the immutable server and console images;
5. starts PostgreSQL and runs the exact server image in one-shot migration mode;
6. starts the server, console, and Caddy edge;
7. verifies the public health endpoint;
8. advances the `current` symlink only after the health check succeeds.

Application rollback restores the previous release. Database down-migrations are never attempted; schema changes must remain backward-compatible through expand/contract migration practices.

## Reset

`deploy/scripts/reset-demo.sh` destroys only the isolated DEMO PostgreSQL volume, recreates the schema through migrations, and restarts the current DEMO release.

A reset may be triggered manually through the workflow. A daily scheduled reset job exists but remains inert unless repository variable `DEMO_RESET_ENABLED` is explicitly set to `true`.

The reset mechanism is host-scoped and does not require an application reset endpoint or reusable reset credential.

## Required GitHub Environment

Create a GitHub Environment named `demo` with these secrets before any live deployment:

- `DEMO_HOST`
- `DEMO_USER`
- `DEMO_SSH_PRIVATE_KEY`
- `DEMO_PUBLIC_HOST`
- `DEMO_ACME_EMAIL`
- `DEMO_DB_PASSWORD`

No real secret values belong in the repository.

## Public-exposure gate

The topology in this sprint is deployable, but it must not be treated as safe for anonymous Internet exposure merely because HTTPS and reset automation exist.

Before public DNS/proxying is enabled, the product must have enforceable demo controls appropriate to the features that exist at that time, including:

- synthetic/fake data only;
- dangerous administration operations disabled or tightly constrained;
- abuse/rate limiting at an appropriate edge or application layer;
- restricted outbound messaging so the demo cannot become a mail/SMS relay;
- no production credentials, connectors, tenants, or customer data;
- predictable periodic reset and operator-triggered recovery;
- logging/telemetry configured so secrets and authentication material are not captured.

`WYRMGATE_DEMO_MODE=true` is passed to the server as an environment profile marker, but it is not itself a security control. Any demo-specific restriction must be implemented and tested server-side before relying on it.

## External activation

A live public DEMO still requires external setup outside this repository:

- a provisioned host;
- DNS/Cloudflare configuration;
- populated `demo` GitHub Environment secrets;
- verification that the selected image SHA exists in GHCR;
- the public-exposure controls above.

Until those are supplied and verified, merging this sprint changes repository capabilities only and does not expose a public service.
