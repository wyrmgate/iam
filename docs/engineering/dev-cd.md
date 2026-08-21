# DEV Continuous Deployment

## Scope

Sprint 9 establishes automatic deployment of a tested `main` revision to the DEV environment after Container CI successfully publishes and scans that revision's immutable GHCR images.

DEV CD never rebuilds application code. It deploys:

- `ghcr.io/wyrmgate/iam-server:sha-<git-sha>`
- `ghcr.io/wyrmgate/iam-console:sha-<git-sha>`

A manual `workflow_dispatch` may redeploy a full 40-character `main` commit SHA when operational recovery requires it.

## Trigger ordering

The deployment workflow listens for successful completion of `Container CI` on `main`. This avoids racing DEV deployment against multi-architecture image publication.

Pull requests run only the DEV deployment-contract validation job. They do not contact a host.

## GitHub Environment

Create a GitHub Environment named `dev` with these secrets:

- `DEV_HOST`: SSH host/IP for the DEV machine
- `DEV_USER`: SSH administrator user with passwordless sudo
- `DEV_SSH_PRIVATE_KEY`: private key used only by GitHub Actions to reach DEV
- `DEV_PUBLIC_HOST`: public DEV hostname
- `DEV_ACME_EMAIL`: email for Caddy ACME registration
- `DEV_DB_PASSWORD`: DEV PostgreSQL password

The workflow uses its short-lived `GITHUB_TOKEN` only during the deployment to authenticate the target host to GHCR, then logs the host out after the deployment attempt.

## Release layout

Each deployment is copied to:

`/opt/wyrmgate/iam/releases/<git-sha>`

Host-only `dev.env` and `edge.env` files are materialized by the workflow and copied with mode `0600`. They are never committed.

`/opt/wyrmgate/iam/current` points to the last release that completed its public health check.

Persistent state is deliberately outside the release identity:

- PostgreSQL volume: `wyrmgate-iam-dev-postgres-data`
- Caddy certificate data: `wyrmgate-caddy-data`
- Caddy runtime config: `wyrmgate-caddy-config`
- public Docker network: `wyrmgate-edge`

## Deployment sequence

1. Pull the immutable server/console images and pinned infrastructure images.
2. Start PostgreSQL and wait for its health check.
3. Run the server image once with `--wyrmgate.migrate-only=true` and no web stack. Spring/Flyway applies database migrations and the process exits.
4. Start the new server and console and wait for container health checks.
5. Start/update Caddy.
6. Call `https://<DEV_PUBLIC_HOST>/actuator/health` repeatedly for up to 150 seconds.
7. Only after the public health check passes, move the `current` symlink to the new release.

## Rollback contract

If migration, startup, or smoke testing fails, the deployment script attempts to restore server, console, and edge configuration from the previous `current` release using the same persistent PostgreSQL and Caddy state.

Database rollback is intentionally not automatic. Flyway migrations must remain backward-compatible under the expand/contract policy so that the previous application revision can run against the newly migrated schema.

## Network boundary

PostgreSQL is reachable only on the DEV internal Docker network. Server joins both internal and edge networks. Console and Caddy join only the edge network. Only Caddy publishes host ports 80/443.

## First deployment prerequisites

Before enabling DEV CD:

- Sprint 6 infrastructure must have created the DEV host/network or an equivalent host must exist.
- Sprint 7 Ansible provisioning must have prepared the host.
- DEV DNS must resolve to the host (directly or through the intended Cloudflare setup) so Caddy can obtain/serve a valid certificate.
- GitHub Environment `dev` secrets must be populated.

The workflow deliberately fails instead of silently skipping deployment if a required environment secret is missing.
