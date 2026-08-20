# Repository and Architecture Boundaries

## Architectural baseline

Wyrmgate IAM starts as a modular monolith. The backend preserves explicit dependency direction across domain, application/use-case, repository/port, infrastructure/adapter, and API layers.

Bounded contexts own their tables and repositories. Cross-context persistence access is prohibited unless explicitly documented. Collaboration occurs through application services, durable domain events, stable internal APIs, or intentional read models.

## Core capability map

The current agreed design centers on:

- identity
- application
- access
- governance
- provisioning
- platform
- audit
- authorization

The model also distinguishes business governance intent from technical realization and provider-observed state.

## Repository map

- `apps/server/` — Java/Spring IAM modular monolith
- `apps/console/` — React IAM console
- `packages/` — narrowly scoped shared/generated artifacts
- `migrations/` — migration support assets and cross-version tooling
- `deploy/` — deployment manifests and runtime configuration templates
- `infra/` — infrastructure provisioning and host configuration
- `scripts/` — developer, CI, release, and operations automation
- `tests/` — cross-cutting integration, E2E, and security regression tests
- `security/` — cross-cutting security engineering assets
- `docs/` — repository-local implementation documentation and ADRs

## Domain invariants

- Identity is the canonical governed who/what.
- Principal is the technical manifestation through which an Identity acts in a target.
- Application is a governable business application/capability, not necessarily one technical endpoint.
- Entitlement is the smallest governable access unit.
- AccessAssignment is durable governance intent.
- EffectiveAccess is derived desired identity-level access.
- Provisioning realizes desired technical state; reconciliation observes provider reality independently.
- Governance decisions never call provider APIs directly.

## Evolution rule

Do not introduce a new top-level module merely because a technical abstraction is convenient. New modules must map to a documented product capability or be justified by an explicit architecture decision.
