# Wyrmgate IAM Documentation

This directory contains the repository-local, implementation-facing documentation for Wyrmgate IAM.

## Documentation policy

Wyrmgate IAM uses three document classes with different purposes:

1. **Repository Markdown** is the living source of truth for architecture, domain semantics, ADRs, implementation contracts, operational runbooks, migration notes, and developer documentation. It changes with code and is reviewed through pull requests.
2. **Formal specifications** are controlled stakeholder/application deliverables maintained in the IAM `Formal Specifications` Drive folder. The current controlled v0.8 package contains the documentation register, BRD, FRD, SRS, DDD, System Architecture & Design, Data Architecture, Security & Governance, Integration & Interface, and Requirements Traceability Matrix.
3. **Collaborative/working Drive documents and architecture images** are design/reconciliation inputs. Once their decisions are promoted into accepted ADRs/repository Markdown, the repository artifacts are the durable implementation-facing authority until the next controlled formal revision.

A concept must not have competing authoritative definitions in multiple formats. Formal specifications define reviewed requirements/design checkpoints; repository Markdown defines the current implementable technical contract. Accepted ADRs may intentionally amend an older formal checkpoint and must be folded into the next controlled revision.

## Documentation map

### Architecture

- [`PROJECT_INSTRUCTIONS.md`](PROJECT_INSTRUCTIONS.md) — stable project-level instructions for AI-assisted work and fresh project sessions.
- [`architecture/overview.md`](architecture/overview.md) — framework-neutral architecture and capability ownership.
- [`architecture/layered-architecture.md`](architecture/layered-architecture.md) — reconciled Layer 1 through Layer 6 architecture, including consolidated Layer 6A–6I.
- [`architecture/repository-boundaries.md`](architecture/repository-boundaries.md) — repository/module dependency rules and implementation boundary.
- [`architecture/implementation-topology-and-persistence.md`](architecture/implementation-topology-and-persistence.md) — initial runtime and persistence direction.
- [`architecture/physical-data-model.md`](architecture/physical-data-model.md) — concrete PostgreSQL-target physical persistence contract and ownership/constraint/index guidance.
- [`architecture/workflow-orchestration.md`](architecture/workflow-orchestration.md) — domain-owned workflow, timers, retries and durable orchestration boundary.
- [`architecture/platform-authority-and-application-tenancy.md`](architecture/platform-authority-and-application-tenancy.md) — ADR-0049 platform/tenant owner authority and shared/internal application tenancy.

### Domain

- [`domain/canonical-model.md`](domain/canonical-model.md) — canonical IAM concepts, ownership of truth, observations, evidence and projections.
- [`domain/state-and-invariants.md`](domain/state-and-invariants.md) — lifecycle, concurrency, structural, temporal and failure invariants.
- [`domain/dynamic-schema-and-provenance.md`](domain/dynamic-schema-and-provenance.md) — governed typed extension schema, mapping and provenance.
- [`domain/tenancy.md`](domain/tenancy.md) — ADR-0051 first-class Tenant identity/lifecycle and operation-scope contract.
- [`domain/access-target-availability.md`](domain/access-target-availability.md) — ADR-0052 Catalog availability and AccessTargetReduction contract.
- [`domain/principal-observation-mapping.md`](domain/principal-observation-mapping.md) — ADR-0050 Integration-owned provider-account-to-Principal technical mapping boundary.
- [`domain/audit-evidence-scope.md`](domain/audit-evidence-scope.md) — ADR-0053 explicit TENANT/DEPLOYMENT Audit evidence ownership.

### Security and API

- [`security/administrative-authorization.md`](security/administrative-authorization.md) — IAM control-plane authorization, scoped administration and owner continuity.
- [`security/control-plane-authentication.md`](security/control-plane-authentication.md) — external/federated and first-party control-plane authentication and bootstrap.
- [`security/first-run-web-setup.md`](security/first-run-web-setup.md) — secured first-run web setup adapter.
- [`security/identity-provider-sso.md`](security/identity-provider-sso.md) — first-party authentication, federation and SSO security boundary.
- [`adr/README.md`](adr/README.md) — canonical ADR index through accepted ADR-0053.
- [`api/api-conventions.md`](api/api-conventions.md) — public/internal API conventions.
- [`api/event-model.md`](api/event-model.md) — domain/internal/public event semantics.
- [`api/idp-sso-contracts.md`](api/idp-sso-contracts.md) — OIDC/OAuth/JWKS/session/claim/secret-boundary implementation contract.
- [`api/identity-contracts.md`](api/identity-contracts.md) — Identity/Principal and source-policy public contracts.
- [`api/catalog-contracts.md`](api/catalog-contracts.md) — Application/ApplicationTarget/Entitlement/Role contracts.
- [`api/access-contracts.md`](api/access-contracts.md) — AccessAssignment and EffectiveAccess contracts.
- [`api/governance-contracts.md`](api/governance-contracts.md) — AccessRequest/Approval/Review/Policy/Exception contracts.
- [`api/credential-contracts.md`](api/credential-contracts.md) — Credential metadata/lifecycle/rotation contract.
- [`api/integration-administration.md`](api/integration-administration.md) — ConnectorInstance/Binding/worker administration contract.
- [`api/connector-worker-protocol.md`](api/connector-worker-protocol.md) — remote connector-worker v1 protocol.
- [`api/administration-contracts.md`](api/administration-contracts.md) — AdministrativeRole/Grant/Delegation/Elevation/BreakGlass contracts.
- [`api/audit-contracts.md`](api/audit-contracts.md) — Audit read/search/export/archive/hold/purge/evidence contracts for the implemented tenant-scoped surface.

### Operations and engineering

- [`operations/fresh-install-bootstrap.md`](operations/fresh-install-bootstrap.md) — first-install activation and owner establishment.
- [`operations/sealed-standalone-owner-recovery.md`](operations/sealed-standalone-owner-recovery.md) — sealed legacy owner recovery boundary.
- [`runbooks/platform-owner-adoption.md`](runbooks/platform-owner-adoption.md) — legacy Platform Owner adoption.
- [`operations/dev-database-reset.md`](operations/dev-database-reset.md) — disposable DEV database rebaseline procedure.
- [`operations/scim-principal-connector.md`](operations/scim-principal-connector.md) — SCIM provider adapter runbook.
- [`operations/backup-recovery.md`](operations/backup-recovery.md), [`operations/production-readiness.md`](operations/production-readiness.md), [`operations/production-recovery-drill.md`](operations/production-recovery-drill.md) — recovery/production readiness.
- [`operations/audit-export.md`](operations/audit-export.md), [`operations/audit-archive.md`](operations/audit-archive.md), [`operations/audit-evidence-lifecycle.md`](operations/audit-evidence-lifecycle.md), [`operations/audit-evidence-integrity.md`](operations/audit-evidence-integrity.md), [`operations/audit-siem.md`](operations/audit-siem.md) — Audit evidence operations.
- [`operations/public-event-webhook.md`](operations/public-event-webhook.md) and [`operations/break-glass-security-notification.md`](operations/break-glass-security-notification.md) — signed outbound delivery runbooks.
- [`engineering/console-ui-work-control.md`](engineering/console-ui-work-control.md), [`engineering/dev-cd.md`](engineering/dev-cd.md), [`engineering/edge.md`](engineering/edge.md), [`engineering/public-demo.md`](engineering/public-demo.md), [`engineering/observability.md`](engineering/observability.md) — implementation/operations engineering contracts.

## Authority hierarchy

When documentation conflicts, resolve it in this order:

1. accepted ADRs and current formal requirements/specifications;
2. current repository architecture/domain/interface contracts;
3. implementation and tests;
4. explicitly retained historical material.

A newly accepted ADR may intentionally be newer than the controlled formal package; that is controlled specification-update debt, not a competing permanent source of truth.

## Framework-neutral architecture rule

Architecture is not defined by Java, Spring, JPA, PostgreSQL, REST, Kafka, Cloudflare, Railway, Neon, OCI, Docker or a particular build layout. Those are implementation/deployment choices. Canonical capability ownership, aggregate boundaries, state semantics, security invariants and cross-capability contracts must remain meaningful if implementation technology changes.

## Reconciled v3 architecture status

The agreed v3 layered architecture reconciliation is now represented durably by the repository ADRs and living Markdown:

- **Layer 4 contains nine business capabilities:** Tenancy, Identity, Catalog, Access, Governance, Credential, Integration, Administration and Audit. Platform is Layer-6 technical support. Authentication/IdP/SSO is a composed service rather than another Layer-4 capability.
- ADR-0050 defines Integration-owned `PrincipalObservationMapping` while Identity remains the sole owner of Principal authority.
- ADR-0051 promotes Tenancy to a first-class capability owning deployment-scoped Tenant identity/lifecycle and trusted TENANT/DEPLOYMENT operation scope.
- ADR-0052 defines Catalog target availability and Access-owned fail-safe `AccessTargetReduction` for terminal target retirement.
- ADR-0053 defines explicit `AuditEvidenceScope = TENANT(ownerTenantId) | DEPLOYMENT` without granting platform operators implicit tenant Audit visibility.
- [`architecture/layered-architecture.md`](architecture/layered-architecture.md) consolidates L1–L6 and Layer 6A–6I. The image-86 internal `Layer 6L` heading is treated as a labeling error; the reconciled area is 6I Runtime/Deployment/Availability/Recovery.

The current controlled formal specification checkpoint remains **v0.8**. ADR-0042 through ADR-0053 are accepted amendments newer than that checkpoint and must be folded into the next controlled BRD/FRD/SRS/DDD/SAD/Data/Security/Integration/RTM revision. Formal requirements/RTM are not silently rewritten by this repository reconciliation.

OD-005 remains open until a concrete production provider environment implements the accepted ADR-0043 objectives and measured recovery/failover evidence proves them. OD-006 legacy migration/cutover remains open.
