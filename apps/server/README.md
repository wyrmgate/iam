# IAM Server

Java/Spring modular-monolith backend for Wyrmgate IAM.

The server preserves the documented dependency direction:

`domain -> application/use-case -> repository/port -> infrastructure/adapter -> API`

Bounded contexts must own their persistence and must not reach directly into another context's repositories. Cross-context collaboration occurs through explicit application services, durable domain events, or intentionally designed read models.

Initial capability areas are expected to include identity, application, access, governance, provisioning, platform, audit, and authorization. Exact source packages and runtime versions will be introduced when the backend build is bootstrapped.

Business rules must not be hidden in controllers, persistence adapters, or UI-facing DTOs.
