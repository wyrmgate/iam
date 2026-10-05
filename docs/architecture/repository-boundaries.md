# Repository and Architecture Boundaries

## Architectural baseline

Wyrmgate IAM begins as a modular monolith, but the architecture is defined by logical capability ownership rather than by framework, package, build-module, persistence or deployment choices.

A later implementation may use Java/Spring, React, a relational database, REST/OpenAPI and OAuth/OIDC libraries, but those technologies do not define the canonical domain boundaries.

## Logical capability map

The current canonical capabilities are:

- Identity
- Catalog
- Access
- Governance
- Credential
- Authentication
- Integration
- Administration
- Audit

Supporting Platform capabilities such as secrets, cryptographic signing, secure random generation, events/outbox, scheduling, notifications, persistence and observability remain replaceable implementation services/ports.

## Boundary rule

Only the owning capability may mutate its authoritative state. Cross-capability collaboration occurs through semantic application contracts, queries, commands, durable events or intentional read projections.

Forbidden patterns include:

- one capability importing another capability's persistence repository to mutate its tables;
- sharing persistence entities as the domain contract;
- Authentication reading or mutating Credential secret/private material directly;
- Credential owning OIDC clients, SSO sessions or authorization grants merely because credentials participate in login;
- Administration owning authentication/session/token state or deriving permissions directly from OAuth scopes/claims;
- AccessAssignments being inferred from successful SSO or token scopes without an explicit governed Access operation;
- Integration updating AccessAssignment because provisioning failed;
- Governance directly calling provider SDKs;
- Observed provider state silently creating desired IAM access;
- Audit records becoming the source of current business state.

## Repository map

- `apps/server/` — backend implementation and capability composition.
- `apps/console/` — IAM web console and same-origin browser session surface.
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
- Principal is a technical manifestation of an Identity in a target or authentication context.
- Credential belongs to Principal and governs authentication-instrument metadata/private-material references; Authentication consumes verification semantically and never owns raw private material.
- Authentication owns login bindings, SSO sessions, OIDC/OAuth client/grant state, stable subject mapping, federation routing and claim-release policy.
- Authentication success does not create business access or IAM administrative authority.
- Application is a governable business capability; ApplicationTarget is a technical target belonging to one Application.
- Entitlement is the smallest governable technical access unit.
- RoleVersion and PolicyVersion become immutable after activation.
- AccessAssignment is durable governance intent; EffectiveAccess and desired technical state are derived projections.
- Provisioning realizes desired state; reconciliation independently observes provider reality.
- Governance decisions do not directly call provider APIs.
- Administrative authorization is a separate IAM control plane and does not use normal IAM roles/access assignments or OAuth/OIDC scopes as platform administration.
- Tenant is a hard isolation boundary; Organization is the business/governance hierarchy.

## Dependency direction

Implementation structure must preserve dependency inversion:

- core domain semantics do not depend on web frameworks, ORM/provider SDKs, OAuth/OIDC library types or message brokers;
- application/use-case logic depends on semantic ports/contracts;
- infrastructure implements external integration, authentication protocol and persistence ports;
- delivery adapters invoke application operations;
- provider SDK/protocol-framework types do not leak into canonical domain contracts.

Cross-capability contracts are defined by consumer needs. Prefer narrow semantic queries such as `IdentityGovernanceContextQuery`, `CredentialAuthenticatorVerifier` or `RoleExpansionQuery` over exposing a foreign entity/repository API.

## State-model boundary

Authoritative state, source/provider observations, immutable evidence and derived projections are distinct categories and may use different physical persistence strategies. They must not be collapsed merely to simplify ORM mapping.

Sensitive bearer-equivalent Authentication artifacts such as authorization codes, refresh tokens and browser session secrets require protected storage/rotation/revocation semantics and are never ordinary resource payloads.

## Evolution rule

Do not introduce a new top-level implementation module/service merely because a technical abstraction is convenient. New physical boundaries must map to a documented capability or provide a concrete operational/security/scaling benefit.

Any material deviation from canonical ownership/state/security invariants requires an ADR before becoming the new architectural baseline.