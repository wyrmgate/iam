# Wyrmgate IAM Documentation

This directory contains the repository-local, implementation-facing documentation for Wyrmgate IAM.

## Documentation policy

Wyrmgate IAM uses three document classes with different purposes:

1. **Repository Markdown** is the living source of truth for architecture, domain semantics, ADRs, implementation contracts, operational runbooks, migration notes, and developer documentation. It changes with code and is reviewed through pull requests.
2. **Formal specifications** are controlled stakeholder/application deliverables maintained in the IAM `Formal Specifications` Drive folder. The current v0.8 package contains the documentation register, BRD, FRD, SRS, DDD, System Architecture & Design, Data Architecture, Security & Governance, Integration & Interface, and the Requirements Traceability Matrix. Superseded v0.7, v0.6, v0.5, v0.4 and v0.3 packages are retained under `Formal Specifications/Archive/`.
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
- [`security/control-plane-authentication.md`](security/control-plane-authentication.md) — external/federated and first-party authentication sources, governed actor binding and burn-once first-admin bootstrap.
- [`security/identity-provider-sso.md`](security/identity-provider-sso.md) — first-party authentication, federation and SSO security boundary introduced by ADR-0042.
- [`adr/README.md`](adr/README.md) — canonical Architecture Decision Record index.
- [`api/api-conventions.md`](api/api-conventions.md) — public/internal API contract conventions.
- [`api/event-model.md`](api/event-model.md) — domain/internal/public event contract semantics.
- [`api/idp-sso-contracts.md`](api/idp-sso-contracts.md) — living first-party OIDC/OAuth, JWKS, governed relying-party, session, claim and secret-boundary implementation contract.
- [`api/identity-contracts.md`](api/identity-contracts.md) — first OD-003 machine-readable Identity OpenAPI/AsyncAPI implementation slice and runtime authorization boundary.
- [`api/credential-contracts.md`](api/credential-contracts.md) — Credential public control-plane resource/operation, secret-reference, concurrency, idempotency and cursor boundary.
- [`api/audit-contracts.md`](api/audit-contracts.md) — AuditRecord, export/archive, legal-hold/purge, EvidenceSnapshot, integrity and SIEM contracts — bounded AuditRecord read/search plus durable asynchronous export/download, authorization, filtering and artifact boundary.
- [`api/administration-contracts.md`](api/administration-contracts.md) — AdministrativeRole/Grant/Delegation/Elevation/BreakGlass semantic control-plane API, concurrency, idempotency, cursor, audit and assurance boundary.
- [`api/catalog-contracts.md`](api/catalog-contracts.md) — authoritative Application/ApplicationTarget/Entitlement/Role runtime contract, revisions, retirement, pagination and provider-observation boundary.
- [`api/access-contracts.md`](api/access-contracts.md) — authoritative AccessAssignment control-plane API and read-only EffectiveAccess projection contract.
- [`api/governance-contracts.md`](api/governance-contracts.md) — self-service AccessRequest and reusable Approval inbox/evidence/decision API contract.
- [`api/connector-worker-protocol.md`](api/connector-worker-protocol.md) — OD-004 remote connector-worker v1 session, leasing, fencing, result, observation, and compatibility contract.
- [`api/integration-administration.md`](api/integration-administration.md) — governed ConnectorInstance/Binding/worker control-plane management API, permissions, revisions, idempotency and secret boundary.
- [`operations/scim-principal-connector.md`](operations/scim-principal-connector.md) — SCIM 2.0 principal and Group provider adapter, observation/membership semantics, secret-resolution edge, reconciliation coverage and provider-error semantics.
- [`engineering/dev-cd.md`](engineering/dev-cd.md) — current DEV deployment contract and retired Railway topology status; no replacement managed server target is canonical yet.
- [`operations/dev-managed-activation.md`](operations/dev-managed-activation.md) — managed DEV activation checklist and recovery verification.
- [`operations/backup-recovery.md`](operations/backup-recovery.md) — managed Neon DEV and standalone PostgreSQL recovery boundaries.
- [`operations/audit-export.md`](operations/audit-export.md) — durable Audit NDJSON export activation, artifact storage, retry, download and verification runbook.
- [`operations/audit-archive.md`](operations/audit-archive.md) — immutable Audit archive-segment generation, retention-policy inputs and verification.
- [`operations/audit-evidence-lifecycle.md`](operations/audit-evidence-lifecycle.md) — legal holds, dual-control purge, archived-read continuity and fail-closed destructive evidence controls.
- [`operations/audit-evidence-integrity.md`](operations/audit-evidence-integrity.md) — typed material snapshots, integrity verification and EvidenceSnapshot operating checks.
- [`operations/audit-siem.md`](operations/audit-siem.md) — signed HTTPS SIEM activation, retry/terminal semantics and receiver requirements.
- [`operations/production-readiness.md`](operations/production-readiness.md) — OD-005 production SLI/SLO/RPO/RTO decision register, failure model, topology gate and service re-entry criteria.
- [`operations/production-recovery-drill.md`](operations/production-recovery-drill.md) — isolation-safe measured production recovery verification and evidence procedure.
- [`operations/public-event-webhook.md`](operations/public-event-webhook.md) — signed public-event webhook activation, receiver, retry, rotation, and failure runbook.
- [`operations/break-glass-security-notification.md`](operations/break-glass-security-notification.md) — ADR-0033 signed security-notification activation, receiver, retry/manual-remediation and secret-rotation runbook.
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

IAM v2 is pre-release and under active design. The v0.8 formal specification set is the current controlled architecture checkpoint. It carries forward v0.7, folds accepted ADR-0038 through ADR-0041 into the formal requirements/design baseline, records the implemented persistence checkpoint through Flyway V60, and incorporates the completed OD-003 public policy/JML control-plane contracts from PR #257. ADR-0042 intentionally broadens that checkpoint so Wyrmgate is an IAM platform with first-party IdP/SSO and IGA capabilities; the next controlled formal revision must fold that product-scope change into BRD/FRD/SRS/DDD/SAD/Security/Integration/RTM. The prior v0.7 Audit/break-glass baseline remains incorporated.

The v0.8 RTM records OD-001, OD-002, OD-003 and OD-004 as resolved at their accepted architecture/interface scope, with OD-005 and OD-006 open. OD-003 is resolved for the currently accepted semantic/interface surface: public Identity/Principal and source correlation/lifecycle/absence policy contracts; Catalog; Access including lifecycle-access policy; Governance request/approval/review plus ACCESS_REQUEST PolicyVersion and GovernanceException; Credential; Integration administration/worker; Administration authority-control; and Audit evidence-lifecycle surfaces are implemented. ADR-0038 adds provenance-fenced restoration of source-caused suspension, ADR-0039 exact DECIMAL/DATE/DATETIME lifecycle predicates, ADR-0040 typed one-value MULTI membership, and ADR-0041 bounded relational typed CONTAINS_ANY/CONTAINS_ALL expected sets. Exact set/subset/count/order predicates, compound expressions, relationship predicates, dynamic reviewer/manager rules, broader exception scopes, SourceSystem administration and GovernanceFinding public APIs remain future product scope unless separately accepted; they are not incomplete OD-003 criteria. Broader BR-level requirements may remain Partially Implemented where their scope exceeds OD-003.

The former Cloudflare Pages/Functions + Railway Serverless + Neon PostgreSQL end-to-end DEV topology is retired because the Railway server is no longer in use. Historical validation remains implementation evidence, but no replacement managed server target is currently canonical. The console Pages contract remains supported independently; production HA/DR remains open under OD-005.

OD-005 production operations is now being advanced through a provider-neutral readiness contract and repeatable recovery-verification drill. Numeric production SLO/RPO/RTO commitments, the production HA/DR topology, backup retention/protection policy and recovery-drill cadence remain explicit reviewed decisions rather than inherited from DEV provider defaults. OD-005 remains open until those decisions are approved and a measured isolation-safe recovery drill plus production alerting/runbooks provide closure evidence.
