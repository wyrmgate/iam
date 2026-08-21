# Repository and Architecture Boundaries

## Architectural baseline

Wyrmgate IAM begins as a modular monolith, but the architecture is defined by logical capability ownership rather than by framework, package, build-module, persistence or deployment choices.

A later implementation may use Java/Spring, React, a relational database and REST/OpenAPI, but those technologies do not define the canonical domain boundaries.

## Logical capability map

The current canonical capabilities are:

- Identity
- Catalog
- Access
- Governance
- Credential
- Integration
- Administration
- Audit

Supporting technical capabilities such as authentication, secrets, events/outbox, scheduling, notifications, persistence and observability remain replaceable implementation services/ports.

## Boundary rule

Only the owning capability may mutate its authoritative state. Cross-capability collaboration occurs through semantic application contracts, queries, commands, durable events or intentional read projections.

Forbidden patterns include:

- one capability importing another capability's persistence repository to mutate its tables;
- sharing persistence entities as the domain contract;
- Integration updating AccessAssignment because provisioning failed;
- Governance directly calling provider SDKs;
- Observed provider state silently creating desired IAM access;
- Audit records becoming the source of current business state.

## Repository map

- `apps/server/` — backend implementation and capability composition.
- `apps/console/` — IAM web console.
- `packages/` — narrowly scoped shared/generated artifacts only.
- `migrations/` — migration support assets and cross-version tooling.
- `deploy/` — deployment manifests and runtime configuration templates.
- `infra/` — infrastructure provisioning and host configuration.
- `scripts/` — developer, CI, release, and operations automation.
- `tests/` — cross-cutting integration, E2E, performance and security tests.
- `security/` — cross-cutting security engineering assets.
- `docs/` — repository-local architecture, domain, security, API, ADR and operations documentation.

Physical build modules/packages are an implementation decision and must not be treated as a permanent one-to-one mapping to canonical capabilities without evidence that the mapping improves enforcement/evolution.

## Domain invariants

- Identity is the canonical governed who/what.
- Principal is a technical manifestation of an Identity in a target.
- Application is a governable business capability; ApplicationTarget is a technical target belonging to one Application.
- Entitlement is the smallest governable technical access unit.
- RoleVersion and PolicyVersion become immutable after activation.
- AccessAssignment is durable governance intent; EffectiveAccess and desired technical state are derived projections.
- Provisioning realizes desired state; reconciliation independently observes provider reality.
- Governance decisions do not directly call provider APIs.
- Administrative authorization is a separate IAM control plane and does not use normal IAM roles/access assignments as platform administration.
- Tenant, when enabled, is a hard isolation boundary; Organization is the business/governance hierarchy.

## Dependency direction

Implementation structure must preserve dependency inversion:

- core domain semantics do not depend on web frameworks, ORM/provider SDKs or message brokers;
- application/use-case logic depends on semantic ports/contracts;
- infrastructure implements external integration and persistence ports;
- delivery adapters invoke application operations;
- provider SDK types do not leak into canonical domain contracts.

Cross-capability contracts are defined by consumer needs. Prefer a narrow semantic query such as `IdentityGovernanceContextQuery` or `RoleExpansionQuery` over exposing a foreign entity/repository API.

## State-model boundary

Authoritative state, source/provider observations, immutable evidence and derived projections are distinct categories and may use different physical persistence strategies. They must not be collapsed merely to simplify ORM mapping.

## Evolution rule

Do not introduce a new top-level implementation module/service merely because a technical abstraction is convenient. New physical boundaries must map to a documented capability or provide a concrete operational/security/scaling benefit.

Any material deviation from canonical ownership/state/security invariants requires an ADR before becoming the new architectural baseline.
