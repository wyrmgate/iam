# Wyrmgate IAM Documentation

This directory contains the repository-local, implementation-facing documentation for Wyrmgate IAM.

## Documentation policy

Wyrmgate IAM uses three document classes with different purposes:

1. **Repository Markdown** is the living source of truth for architecture, domain semantics, ADRs, implementation contracts, operational runbooks, migration notes, and developer documentation. It changes with code and is reviewed through pull requests.
2. **Formal specifications** are controlled stakeholder/application deliverables maintained in the IAM `Formal Specifications` Drive folder. The current v0.2 package contains the documentation register, BRD, FRD, SRS, DDD, System Architecture & Design, Data Architecture, Security & Governance, Integration & Interface, and the Requirements Traceability Matrix.
3. **Collaborative Google Docs** are working notes only. A working note is not authoritative after its decisions have been promoted.

A concept must not have competing authoritative definitions in multiple formats. Formal specifications define reviewed requirements/design baselines; repository Markdown defines the current implementable technical contract. Accepted ADRs may intentionally amend a formal specification between releases and must be folded into the next formal revision.

## Documentation map

- [`PROJECT_INSTRUCTIONS.md`](PROJECT_INSTRUCTIONS.md) — stable project-level instructions.
- [`architecture/overview.md`](architecture/overview.md) — framework-neutral system architecture and capability ownership.
- [`architecture/repository-boundaries.md`](architecture/repository-boundaries.md) — repository/module dependency rules.
- [`architecture/implementation-topology-and-persistence.md`](architecture/implementation-topology-and-persistence.md) — initial runtime and persistence direction.
- [`architecture/physical-data-model.md`](architecture/physical-data-model.md) — concrete PostgreSQL physical persistence contract.
- [`architecture/workflow-orchestration.md`](architecture/workflow-orchestration.md) — domain-owned workflow and durable orchestration boundary.
- [`domain/canonical-model.md`](domain/canonical-model.md) — canonical IAM concepts and ownership of truth.
- [`domain/state-and-invariants.md`](domain/state-and-invariants.md) — lifecycle, concurrency, structural, temporal, and failure invariants.
- [`security/administrative-authorization.md`](security/administrative-authorization.md) — IAM control-plane authorization and scoped administration.
- [`adr/README.md`](adr/README.md) — canonical Architecture Decision Record index.
- [`api/api-conventions.md`](api/api-conventions.md) — public/internal API contract conventions.
- [`api/event-model.md`](api/event-model.md) — event contract semantics.
- [`engineering/dev-cd.md`](engineering/dev-cd.md) — active managed DEV deployment topology: Cloudflare Pages, Railway, and Neon.
- [`operations/dev-managed-activation.md`](operations/dev-managed-activation.md) — first managed DEV activation checklist.
- [`engineering/edge.md`](engineering/edge.md) — standalone-host Caddy reference edge.
- [`engineering/public-demo.md`](engineering/public-demo.md) — current DEV/demo usage and standalone DEMO reference status.
- [`engineering/observability.md`](engineering/observability.md) — telemetry contract and current managed DEV posture.
- `operations/` — deployment, backup/restore, monitoring, incident, connector, and reconciliation runbooks as capabilities are implemented.

## Authority hierarchy

When documentation conflicts, resolve it in this order:

1. accepted ADRs and current formal requirements/specifications;
2. current repository architecture/domain/interface contracts;
3. implementation and tests;
4. explicitly retained historical material.

## Framework-neutral architecture rule

The architecture is not defined by Java, Spring, JPA, PostgreSQL, REST, Kafka, Cloudflare, Railway, Neon, OCI, Docker, or a particular build layout. Those are implementation/deployment choices. Canonical capability ownership, aggregate boundaries, state semantics, security invariants, and cross-capability contracts remain provider-neutral.

## Current status

IAM v2 is pre-release and under active design. The v0.2 formal specification set plus accepted ADR-0001 through ADR-0010 are the current architecture checkpoint. The first real DEV/testing/demo deployment direction is now a managed-service implementation topology documented in `engineering/dev-cd.md`; this does not settle production HA/DR or change canonical IAM capability architecture.

Remaining open design areas include machine-readable OpenAPI/AsyncAPI schemas (OD-003), the remote connector-worker protocol (OD-004), operations/HA/DR (OD-005), and legacy migration/cutover (OD-006).
