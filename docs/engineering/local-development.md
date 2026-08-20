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
