# IAM Server

Java/Spring modular-monolith backend for Wyrmgate IAM.

## Toolchain

- Java 25 LTS
- Spring Boot 4.1.x
- Apache Maven 3.9.x
- PostgreSQL
- Flyway
- Spring JDBC
- Spring Boot Actuator
- OpenAPI via springdoc-openapi
- JUnit 5, ArchUnit, and PostgreSQL Testcontainers

The server preserves the documented dependency direction:

`domain -> application/use-case -> repository/port -> infrastructure/adapter -> API`

Bounded contexts own their persistence and must not reach directly into another context's repositories. Cross-context collaboration occurs through explicit application services, durable domain events, or intentionally designed read models.

Initial capability areas include identity, catalog, access, governance, credential, integration, administration, audit, and platform. New Java code uses the `io.wyrmgate.iam` namespace.

Business rules must not be hidden in controllers, persistence adapters, or UI-facing DTOs.

## Persistence foundation

The initial PostgreSQL physical topology follows ADR-0010. Capability schemas are created by Flyway while capability-specific aggregate tables are added only with their owning use cases.

Platform infrastructure currently provides:

- explicit `platform.tenant` isolation roots;
- application-side UUIDv7 ID generation with opaque ID semantics;
- explicit `TenantContext` values rather than thread-local tenant state;
- revision-guarded JDBC update support that rejects stale writes;
- a transaction executor for bounded authoritative state + outbox commits;
- transactional outbox, inbox/deduplication, causal idempotency, and technical scheduled-work leasing;
- PostgreSQL integration tests that run the real Flyway history from an empty database.

The Platform persistence package is infrastructure, not a generic domain repository layer. Do not add `BaseEntity`, `GenericRepository`, cross-capability DAOs, or framework types to domain contracts.

## Commands

From the repository root:

```bash
make server-build
make server-test
make server-run
```

`server-build` and `server-test` require Docker because the PostgreSQL persistence contract is verified with Testcontainers. `server-run` requires the local PostgreSQL stack; the root Makefile starts it automatically through `dev-up`.
