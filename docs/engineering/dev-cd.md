# DEV Continuous Deployment

## Scope

DEV CD deploys a tested immutable `main` revision to the protected GitHub Environment named `dev` after Container CI has successfully published and scanned that revision's GHCR images.

DEV CD never rebuilds application code. It deploys:

- `ghcr.io/wyrmgate/iam-server:sha-<git-sha>`
- `ghcr.io/wyrmgate/iam-console:sha-<git-sha>`

A manual `workflow_dispatch` may redeploy a full 40-character commit SHA only when that SHA is reachable from `main`. Operators must choose a SHA whose required CI, especially Container CI, is green; manual dispatch is an operational redeploy path, not a way to bypass verification.

## Trigger ordering

The deployment workflow listens for successful completion of `Container CI` on `main`. This avoids racing DEV deployment against multi-architecture image publication.

Pull requests run only the DEV deployment-contract validation job. They do not contact a host.

## GitHub Environment contract

Create a GitHub Environment named `dev`. The current workflow reads these exact Environment secrets:

- `DEV_HOST` — SSH host or IP for the DEV machine. This exact value must have a matching entry in `DEV_SSH_KNOWN_HOSTS`.
- `DEV_USER` — SSH administrator user with passwordless `sudo`.
- `DEV_SSH_PRIVATE_KEY` — private key used only by GitHub Actions to reach DEV.
- `DEV_SSH_KNOWN_HOSTS` — independently verified OpenSSH `known_hosts` line or lines pinning the DEV host identity.
- `DEV_PUBLIC_HOST` — public DEV DNS hostname served by Caddy.
- `DEV_ACME_EMAIL` — email used for Caddy ACME registration.
- `DEV_DB_PASSWORD` — DEV PostgreSQL password.
- `DEV_OTEL_EXPORTER_ENDPOINT` — OTLP/HTTP base endpoint used by the runtime collector.
- `DEV_OTEL_EXPORTER_AUTHORIZATION` — complete authorization value required by the selected OTLP backend.

Do not put the values in repository examples, issues, pull requests, CI logs, or chat. Configure them directly in the protected GitHub Environment.

The workflow deliberately fails if any required value is absent. The pull-request contract also checks that every referenced `DEV_...` Environment secret remains documented here.

## Pinned SSH host trust

DEV CD does not discover or trust host keys during deployment. Runtime dynamic host-key discovery is forbidden.

Before configuring `DEV_SSH_KNOWN_HOSTS`, obtain the host public-key fingerprint through an independent trusted channel, compare it with a separately captured candidate key, and only then store the verified `known_hosts` entry in the `dev` Environment. The first-live procedure is detailed in [`../operations/dev-live-activation.md`](../operations/dev-live-activation.md).

At runtime the workflow:

1. writes the supplied private key to job-local `~/.ssh/id_ed25519` with mode `0600`;
2. writes `DEV_SSH_KNOWN_HOSTS` to job-local `~/.ssh/known_hosts` with mode `0600`;
3. validates that the file is parseable and contains an entry for the exact `DEV_HOST`;
4. uses `BatchMode=yes`, `IdentitiesOnly=yes`, `StrictHostKeyChecking=yes`, and the explicit known-hosts file for every SSH operation.

A missing, malformed, unknown, or changed host key therefore fails the deployment closed.

## Revision provenance

`TARGET_SHA` must be exactly 40 lowercase hexadecimal characters. The workflow checks out that revision with full history and verifies it is an ancestor of `origin/main`.

Automatic deployments inherit their SHA from the successful Container CI `workflow_run`. For manual recovery/redeployment, select an immutable SHA from `main` after confirming required checks were successful for that revision.

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

1. Copy the immutable deployment bundle to `/opt/wyrmgate/iam/releases/<git-sha>`.
2. Authenticate the host to GHCR with the job-scoped `GITHUB_TOKEN`.
3. Pull the immutable server/console images and pinned infrastructure images.
4. Start PostgreSQL and the OpenTelemetry Collector and wait for health checks.
5. Run the server image once with `--wyrmgate.migrate-only=true`. Spring/Flyway applies database migrations and the process exits.
6. Start the new server and console and wait for container health checks.
7. Start/update Caddy.
8. Call `https://<DEV_PUBLIC_HOST>/actuator/health` repeatedly for up to 150 seconds.
9. Only after the public health check passes, move the `current` symlink to the new release.
10. Log the host out of GHCR after the deployment attempt.

## Rollback contract

If migration, startup, or smoke testing fails and a previous `current` release exists, the deployment script attempts to restore server, console, and edge configuration from that previous healthy release using the same persistent PostgreSQL and Caddy state.

If the first-ever deployment fails and there is no previous healthy release, the script stops the partial application and edge stacks rather than leaving an unverified public deployment running. Named PostgreSQL/Caddy volumes are not deleted.

Database rollback is intentionally not automatic. Flyway migrations must remain backward-compatible under the expand/contract policy so that the previous application revision can run against a newly migrated schema.

## Network and TLS boundary

PostgreSQL and the OpenTelemetry Collector are reachable only on the DEV internal Docker network. Server joins both internal and edge networks. Console and Caddy join the edge network. Only Caddy publishes host ports 80/443.

Before the first deployment, `DEV_PUBLIC_HOST` must resolve to the intended DEV host and inbound TCP 80/443 must be reachable so Caddy can complete ACME validation and serve a trusted certificate. Do not start DEV CD before DNS/TLS prerequisites are ready.

## Observability boundary

The DEV Collector requires a usable OTLP/HTTP endpoint and authorization value. Collector process health does not by itself prove backend delivery. After activation, verify traces/metrics at the backend as described in [`observability.md`](observability.md).

## First-live activation

The ordered infrastructure, SSH trust, Ansible, DNS, observability, GitHub Environment, first deployment, backup/restore, reboot, and rollback procedure is [`../operations/dev-live-activation.md`](../operations/dev-live-activation.md).

Repository CI never runs `tofu apply`, provisions a live host, changes public DNS, or deploys DEV from a pull request.
