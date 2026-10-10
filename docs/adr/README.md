# Architecture Decision Records

This directory is the canonical home for Wyrmgate IAM architecture decisions. The previous Google Drive ADR library is superseded by these repository ADRs.

## Status model

Each ADR is `Proposed`, `Accepted`, `Superseded`, or `Rejected`.

## Current canonical ADRs

- [ADR-0001 Framework-neutral capability architecture](0001-framework-neutral-capability-architecture.md)
- [ADR-0002 Canonical identity, principal, role and access model](0002-canonical-identity-principal-access-model.md)
- [ADR-0003 Governance state, validity, fulfillment and observation separation](0003-state-validity-fulfillment-observation.md)
- [ADR-0004 Desired-state integration, connectors and reconciliation](0004-desired-state-integration-and-reconciliation.md)
- [ADR-0005 Administrative authorization and tenant isolation](0005-administrative-authorization-and-tenant-isolation.md)
- [ADR-0006 Credential secret boundary, audit and evidence](0006-credential-audit-evidence-boundary.md)
- [ADR-0007 Dynamic schema, mapping, authority and provenance](0007-dynamic-schema-and-provenance.md)
- [ADR-0008 API and event contract model](0008-api-and-event-contract-model.md)
- [ADR-0009 Domain-owned workflow and durable orchestration](0009-domain-owned-workflow-and-durable-orchestration.md)
- [ADR-0010 Physical data model and persistence boundaries](0010-physical-data-model-and-persistence-boundaries.md)
- [ADR-0011 Control-plane authentication and initial administrator bootstrap](0011-control-plane-authentication-and-initial-admin-bootstrap.md)
- [ADR-0012 Integrity-protected API continuation cursors](0012-integrity-protected-api-continuation-cursors.md)
- [ADR-0013 Initial public integration-event transport and compatibility lifecycle](0013-initial-public-event-webhook-transport.md)
- [ADR-0014 Remote connector-worker protocol and compatibility](0014-remote-connector-worker-protocol.md)
- [ADR-0015 Provider observation mapping and governance drift boundary](0015-provider-observation-mapping-and-governance-drift.md)
- [ADR-0016 Reusable typed approval orchestration within Governance](0016-reusable-typed-approval-orchestration.md)
- [ADR-0017 Versioned Governance policy, risk and SoD evaluation](0017-versioned-governance-policy-risk-sod-evaluation.md)
- [ADR-0018 Scoped time-bound GovernanceException lifecycle](0018-scoped-time-bound-governance-exception-lifecycle.md)
- [ADR-0019 Scalable Identity access review and current-state remediation](0019-scalable-identity-access-review-current-state-remediation.md)
- [ADR-0020 Credential authority, external secret references and durable rotation](0020-credential-authority-external-secret-reference-durable-rotation.md)
- [ADR-0021 Identity lifecycle eligibility and durable Access reduction](0021-identity-lifecycle-eligibility-durable-access-reduction.md)
- [ADR-0022 Source-driven Identity construction and lifecycle orchestration](0022-source-driven-identity-construction-lifecycle-orchestration.md)
- [ADR-0023 Source-driven Identity lifecycle policy](0023-source-driven-identity-lifecycle-policy.md)
- [ADR-0024 Trusted COMPLETE source absence inference](0024-trusted-complete-source-absence-inference.md)
- [ADR-0025 Access-owned lifecycle policy and governed Joiner/Mover reconciliation](0025-access-owned-lifecycle-policy-joiner-mover-reconciliation.md)
- [ADR-0026 Explicit Identity merge/split with historical reference preservation](0026-explicit-identity-merge-split-history-preservation.md)
- [ADR-0027 Provenance-fenced source reactivation after trusted absence inference](0027-provenance-fenced-source-reactivation.md)
- [ADR-0028 Governance approval for lifecycle-policy privilege increases](0028-governance-approval-lifecycle-policy-privilege-increase.md)
- [ADR-0029 Typed scalar equality for lifecycle-access policy predicates](0029-typed-scalar-lifecycle-access-policy-predicates.md)
- [ADR-0030 Classification-scoped canonical attribute value authorization](0030-classification-scoped-canonical-value-authorization.md)
- [ADR-0031 Append-only AuditRecord foundation and replay semantics](0031-append-only-audit-record-foundation.md)
- [ADR-0032 Delegated, elevated and emergency administrative authority](0032-delegated-elevated-emergency-administrative-authority.md)
- [ADR-0033 Break-glass notification delivery and post-use review completion](0033-break-glass-notification-post-use-review.md)
- [ADR-0034 Durable Audit export and immutable evidence archive](0034-durable-audit-export-immutable-archive.md)
- [ADR-0035 Audit legal hold, destructive purge and archived query continuity](0035-audit-legal-hold-purge-archived-query.md)
- [ADR-0036 Audit EvidenceSnapshot, material display snapshot and integrity metadata](0036-audit-evidence-snapshot-material-integrity.md)
- [ADR-0037 Audit SIEM delivery boundary](0037-audit-siem-delivery.md)
- [ADR-0038 Provenance-fenced source suspension restoration](0038-provenance-fenced-source-suspension-restoration.md)
- [ADR-0039 Exact DECIMAL, DATE and DATETIME lifecycle-access predicates](0039-exact-temporal-decimal-lifecycle-predicates.md)
- [ADR-0040 Typed MULTI membership for lifecycle-access policy](0040-typed-multi-membership-lifecycle-access-predicates.md)
- [ADR-0041 Typed MULTI expected-set lifecycle-access predicates](0041-typed-multi-expected-set-lifecycle-access-predicates.md)
- [ADR-0042 First-party identity provider and single sign-on](0042-first-party-identity-provider-and-sso.md)
- [ADR-0043 Production HA/DR and recovery objectives](0043-production-ha-dr-and-recovery-objectives.md)

Formal specification documents are versioned separately in the IAM `Formal Specifications` Drive folder. The current controlled checkpoint is v0.8. It carries forward v0.7 and folds ADR-0038 through ADR-0041 plus the completed OD-003 public policy/JML control-plane contract tranche into the formal specification set, with the persistence checkpoint through Flyway V60. ADR-0042 intentionally amends that v0.8 baseline by broadening Wyrmgate from an IGA-focused product to an IAM platform with first-party IdP/SSO capability. ADR-0043 adds the reviewed OD-005 production service/recovery objective and provider-neutral HA/DR topology baseline. Both decisions must be folded into the next controlled formal revision. A later accepted ADR may amend the formal baseline between controlled document revisions and must be folded into the next meaningful formal revision.
