# Wyrmgate IAM Documentation

This directory contains the repository-local, implementation-facing documentation for Wyrmgate IAM.

## Documentation policy

Wyrmgate IAM uses three document classes with different purposes:

1. **Repository Markdown** is the living source of truth for architecture, domain semantics, ADRs, implementation contracts, operational runbooks, migration notes, and developer documentation. It changes with code and is reviewed through pull requests.
2. **Formal specifications** are controlled stakeholder/application deliverables maintained in the IAM `Formal Specifications` Drive folder. The current v0.2 package contains the documentation register, BRD, FRD, SRS, DDD, System Architecture & Design, Data Architecture, Security & Governance, Integration & Interface, and the Requirements Traceability Matrix.
3. **Collaborative Google Docs** are working notes only. A working note is not authoritative after its decisions have been promoted. The former Drive ADR/working-baseline collection is superseded by the repository ADRs and formal specification set.

A concept must not have competing authoritative definitions in multiple formats. Formal specifications define reviewed requirements/design baselines; repository Markdown defines the current implementable technical contract. Accepted ADRs may intentionally amend a formal specification between releases and must be folded into the next formal revision.

## Documentation map

- [`PROJECT_INSTRUCTIONS.md`](PROJECT_INSTRUCTIONS.md) — stable project-level instructions for AI-assisted work and fresh project sessions.
- [`architecture/overview.md`](architecture/overview.md) — framework-neutral system architecture and capability ownership.
- [`architecture/repository-boundaries.md`](architecture/repository-boundaries.md) — repository/module dependency rules and implementation boundary.
- [`architecture/implementation-topology-and-persistence.md`](architecture/implementation-topology-and-persistence.md) — initial runtime and persistence direction.
- [`architecture/physical-data-model.md`](architecture/physical-data-model.md) — concrete PostgreSQL-target physical persistence contract, ownership matrix, constraints, indexes, partitioning, retention and transaction rules for OD-002.
- [`architecture/workflow-orchestration.md`](architecture/workflow-orchestration.md) — domain-owned workflow, timers, retries and durable orchestration boundary.
- [`domain/canonical-model.md`](domain/canonical-model.md) — canonical IAM concepts, ownership of truth, observations, evidence, and projections.
- [`domain/state-and-invariants.md`](domain/state-and-invariants.md) — lifecycle, concurrency, structural, temporal, and failure invariants.
- [`security/administrative-authorization.md`](security/administrative-authorization.md) — IAM control-plane authorization and scoped administration.
- [`security/control-plane-authentication.md`](security/control-plane-authentication.md) — external bearer authentication, governed actor binding and burn-once first-admin bootstrap.
- [`adr/README.md`](adr/README.md) — canonical Architecture Decision Record index.
- [`api/api-conventions.md`](api/api-conventions.md) — public/internal API contract conventions.
- [`api/event-model.md`](api/event-model.md) — domain/internal/public event contract semantics.
- [`api/identity-contracts.md`](api/identity-contracts.md) — first OD-003 machine-readable Identity OpenAPI/AsyncAPI implementation slice and runtime authorization boundary.
- [`engineering/dev-cd.md`](engineering/dev-cd.md) — active managed DEV deployment topology: Cloudflare Pages, Railway, and Neon.
- [`operations/dev-managed-activation.md`](operations/dev-managed-activation.md) — managed DEV activation checklist and recovery verification.
- [`operations/backup-recovery.md`](operations/backup-recovery.md) — managed Neon DEV and standalone PostgreSQL recovery boundaries.
- [`engineering/edge.md`](engineering/edge.md) — standalone-host Caddy reference edge.
- [`engineering/public-demo.md`](engineering/public-demo.md) — current DEV/demo usage and standalone DEMO reference status.
- [`engineering/observability.md`](engineering/observability.md) — vendor-neutral telemetry contract and current managed DEV posture.
- `operations/` — deployment, backup/restore, monitoring, incident, connector, and reconciliation runbooks as those capabilities are implemented.

## Authority hierarchy

When documentation conflicts, resolve it in this order:

1. accepted ADRs and current formal requirements/specifications;
2. current repository architecture/domain/interface contracts;
3. implementation and tests;
4. explicitly retained historical material.

A newly accepted ADR may temporarily be newer than a formal document; that is controlled specification-update debt, not a competing permanent source of truth.

## Framework-neutral architecture rule

The architecture is not defined by Java, Spring, JPA, PostgreSQL, REST, Kafka, Cloudflare, Railway, Neon, OCI, Docker, or a particular build layout. Those are implementation/deployment choices. Canonical capability ownership, aggregate boundaries, state semantics, security invariants, and cross-capability contracts must remain meaningful if an implementation or deployment technology changes.

## Current status

IAM v2 is pre-release and under active design. The v0.2 formal specification set plus accepted ADR-0001 through ADR-0012 are the current architecture checkpoint. ADR-0011 and ADR-0012 are controlled post-v0.2 amendments and must be folded into the next formal Security/SAD/Integration/RTM revision.

The v0.2 formal RTM predates ADR-0010 and still lists OD-002 as open; ADR-0010 and [`architecture/physical-data-model.md`](architecture/physical-data-model.md) are the current controlled amendment, and the next formal-specification revision must fold them into the Data Architecture/SAD/RTM package.

The first real DEV/testing/demo environment is activated on the managed-service topology documented in [`engineering/dev-cd.md`](engineering/dev-cd.md): Cloudflare Pages/Functions, Railway Serverless, and Neon PostgreSQL. The deployment path, same-origin API proxy, Railway health path, database connectivity, and Flyway startup migration path have been validated. This DEV/demo deployment choice does not settle production HA/DR or change canonical IAM capability architecture. Grafana/OTLP remains intentionally disabled in managed DEV pending deliberate serverless-idle validation.

OD-003 is **partially implemented**. The first Identity OpenAPI/AsyncAPI slice is checked in, Administration has a persisted default-deny direct-grant evaluator, and the control plane now has provider-neutral JWT bearer validation, an Administration-owned server-side issuer+subject binding to tenant + governed Identity, and a burn-once first-administrator bootstrap path. OAuth/OIDC claims do not become Wyrmgate administrative permissions.

The first runtime Identity HTTP slice is now implemented for the checked-in contract: create/read/list, display-name update, and canonical-attribute metadata reads. Each operation consumes the trusted authenticated actor context and re-evaluates `AdministrativeAuthorizationService`; bearer possession alone is never authorization. Mutations use causal idempotency and revision semantics. Collection cursors are deterministic, integrity-protected, tenant/context-bound, time-bounded transport tokens under ADR-0012. Canonical values remain fail-closed/redacted until classification-aware value visibility exists.

Remaining open design areas therefore include completion of OD-003 coverage beyond this Identity runtime slice and external event publication/compatibility process, the remote connector-worker protocol (OD-004), operations/HA/DR (OD-005), and legacy migration/cutover (OD-006). Migration entities/repositories and concrete SQL migrations should follow the persistence semantics in OD-002 as the implementation contract.
