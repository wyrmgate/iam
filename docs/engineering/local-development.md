# Local Development

## Goal

Provide a reproducible developer environment without requiring application containers during normal coding. Docker Compose owns infrastructure; the Spring server and React console run on the host for fast reload and straightforward debugging.

## Prerequisites

- JDK 25
- Node.js 24 LTS
- Docker with Compose v2
- `make`
- `curl` or `wget`
- `unzip`

Maven does not need to be installed globally. `apps/server/mvnw` bootstraps the repository-pinned Maven 3.9.16 distribution into the developer Maven cache.

Docker must also be available when running server verification because persistence integration tests use a disposable PostgreSQL Testcontainer. The test database is isolated from the normal local-development PostgreSQL volume.

## First-time setup

```bash
make dev-init
make dev-up
make console-install
```

`make dev-init` copies `deploy/config/local.env.example` to the ignored `deploy/config/local.env` file if it does not exist.

## Local infrastructure

The Compose stack provides:

- PostgreSQL on `127.0.0.1:5432`
- Valkey on `127.0.0.1:6379`
- Mailpit SMTP on `127.0.0.1:1025`
- Mailpit UI on `127.0.0.1:8025`
- OTLP gRPC on `127.0.0.1:4317`
- OTLP HTTP on `127.0.0.1:4318`

All development service ports bind to loopback only. They are not intended for LAN/public exposure.

## Running applications

Terminal 1:

```bash
make server-run
```

This starts the infrastructure if necessary, loads the local environment, and runs Spring Boot. Flyway applies pending migrations during application startup.

Terminal 2:

```bash
make console-dev
```

The Vite development server proxies API calls to the Spring server.

## Database migrations

Flyway migration history is append-only. Do not edit or renumber an already merged migration. The current foundation uses sequential `V<version>__<description>.sql` files under `apps/server/src/main/resources/db/migration`.

Platform/bootstrap migrations may establish physical topology and Platform-owned technical infrastructure. Capability-owned tables are introduced later by capability-focused migrations and must preserve the ownership rules in ADR-0010 and `docs/architecture/physical-data-model.md`.

A normal server startup applies pending migrations. DEV deployment also runs the existing one-shot migration mode before starting the application container.

## Persistence verification

Run the full server verification with:

```bash
make server-build
```

The persistence integration tests start PostgreSQL 18.4 in Testcontainers, apply the real Flyway migration history to an empty database, validate the resulting schema, and verify tenant constraints, optimistic concurrency, transactional outbox rollback, inbox/idempotency uniqueness, and concurrent scheduled-work claiming. H2 is not used as a PostgreSQL substitute.

Because the Testcontainer is disposable, these tests are the safe clean-bootstrap check; they do not run Flyway `clean` against local, DEV, DEMO, or production databases.

## Operations

```bash
make dev-status
make dev-logs
make dev-down
make dev-reset
```

`dev-reset` is destructive: it removes the local PostgreSQL volume and recreates the infrastructure stack.

## Local credentials

The example database password is intentionally weak and local-only. It must never be reused in dev servers, demo, staging, production, CI secrets, connector configuration, or signing-key material.

## Design boundary

Valkey is available as an optional local infrastructure capability, but application correctness must not depend on it unless a feature explicitly introduces that dependency. PostgreSQL remains the correctness baseline.
