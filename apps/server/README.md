# IAM Server

Java/Spring modular-monolith backend for Wyrmgate IAM.

## Toolchain

- Java 25 LTS
- Spring Boot 4.1.x
- Gradle Kotlin DSL
- PostgreSQL
- Flyway
- Spring JDBC
- Spring Boot Actuator
- OpenAPI via springdoc-openapi
- JUnit 5 and ArchUnit

The server preserves the documented dependency direction:

`domain -> application/use-case -> repository/port -> infrastructure/adapter -> API`

Bounded contexts own their persistence and must not reach directly into another context's repositories. Cross-context collaboration occurs through explicit application services, durable domain events, or intentionally designed read models.

Initial capability areas include identity, application, access, governance, provisioning, platform, audit, and authorization. New Java code uses the `io.wyrmgate.iam` namespace.

Business rules must not be hidden in controllers, persistence adapters, or UI-facing DTOs.

## Commands

From the repository root:

```bash
make server-build
make server-test
make server-run
```

`server-run` requires a reachable PostgreSQL database. The Docker-based local database is introduced by the local-development infrastructure sprint.
