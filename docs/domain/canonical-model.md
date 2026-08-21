# Canonical IAM Domain Model

## Purpose

This document defines the canonical concepts used throughout IAM v2 independently of framework or persistence technology.

## Identity

`Identity` is the canonical governed subject. Top-level types are `PERSON`, `SERVICE`, and `WORKLOAD`. Identity type is stable for normal operations and determines exactly one compatible typed profile.

A canonical Identity may be represented by many `Principal` objects in external targets. `Principal` is a technical representation, not the governed subject itself.

Identity-source construction uses `SourceSystem`, `SourceRecord`, `IdentityLink`, attribute mapping, correlation and attribute-authority rules. Mapping, correlation and authority are separate concerns. Source records preserve what external sources reported and never become canonical state merely because they were imported.

## Organization and relationships

`Organization` is business/governance structure, not tenant isolation. Identity-to-organization relationships are typed and temporal; a single `organizationId` attribute is insufficient to model legal employer, business unit, department, project or other relationships.

Manager and ownership relationships preserve provenance. Non-human identities require accountable ownership according to policy.

## Application catalog

`Application` is a governable business application or capability. `ApplicationTarget` is a technical target/environment belonging to exactly one Application. A single connector may serve multiple targets through explicit `ConnectorBinding` objects.

`Entitlement` is the smallest governable technical access unit. It belongs to one Application and optionally one compatible Target.

IAM roles are business catalog constructs:

- `BUSINESS` Role — organizational/job-function access package that may span applications.
- `APPLICATION` Role — business-facing access package within one Application.

Role composition is versioned through immutable `RoleVersion` objects. Initial composition is constrained to BUSINESS → APPLICATION Role, BUSINESS → Entitlement, and APPLICATION → Entitlement.

## Access intent and realization

`AccessAssignment` is authoritative governance intent. It always belongs to one Identity and targets exactly one Role or Entitlement. It may constrain technical realization to `ANY`, `STANDARD`, `PRIVILEGED`, or a `SPECIFIC` Principal.

Multiple assignments may legitimately support the same access because they preserve different causes, such as birthright policy and approved request.

`EffectiveAccess` is not an aggregate; it is a rebuildable projection derived from assignments and active RoleVersion composition while retaining supporting provenance paths.

`DesiredPrincipalState` and `DesiredGrantState` are technical desired-state projections. They are compared with provider observation by Integration.

## Governance

Governance owns requests, approvals, reviews, policy/versioning, SoD/risk evaluation orchestration, exceptions and findings.

`AccessRequest` has one beneficiary and one or more `RequestItem` objects. Approval plans are decision-time workflow snapshots. `ApprovalDecision` and `ReviewDecision` are immutable evidence.

`GovernanceException` is an explicit, scoped, time-bound deviation and never removes the underlying violation. `GovernanceFinding` is the common actionable issue lifecycle for drift, missing ownership, stale access, credential issues and policy violations.

`RiskAssessment` explains risk; policy decides what IAM does about that risk. `SoDViolation` represents a present conflict while GovernanceFinding handles acknowledgment/remediation/acceptance workflow.

## Credential

`Credential` is a durable authentication instrument/configuration belonging to one Principal. IAM governs metadata, lifecycle, policy, risk and rotation but does not store raw secret/private material by default. Secret material is represented by opaque `SecretReference` values and handled by external secret-provider capabilities.

Rotation is a durable `CredentialRotation` orchestration rather than a `ROTATING` credential status because old and replacement credentials can coexist during cutover.

## Integration and observation

`ConnectorInstance` is configured integration state; `ConnectorBinding` binds a connector capability to a SourceSystem or ApplicationTarget.

Integration owns `ProvisioningJob`, `ProvisioningTask`, immutable `ProvisioningAttempt`, `ReconciliationRun`, and provider observations such as `ObservedPrincipal`, `ObservedEntitlement`, `ObservedGrant`, and `ObservedCredential`.

Observed state never silently overwrites governed desired state.

## Administration

`AdministrativeRole`, `AdministrativePermission`, `AdministrativeGrant`, `AdministrativeScope`, and `AdministrativeDelegation` govern permission to operate IAM itself. These concepts remain distinct from normal IAM roles, entitlements and access assignments.

## Audit and evidence

`DomainEvent`, `AuditRecord`, `EvidenceSnapshot`, and technical operational logging have different semantics:

- Domain events drive internal reactions.
- Audit records provide immutable governed/security evidence.
- Evidence snapshots capture decision-time context.
- Operational logs diagnose execution.

IAM is event-driven where useful but does not require event sourcing as its authoritative persistence model.

## Canonical classification

### Authoritative/stateful

Identity, Organization, IdentityLink, Principal, Application, ApplicationTarget, Entitlement, Role/RoleVersion, AccessAssignment, request/review workflow state, Policy/PolicyVersion, SoDRule, GovernanceException, GovernanceFinding, Credential, ConnectorInstance/Binding, provisioning/reconciliation state, AdministrativeRole/Grant/Delegation.

### Observation

SourceRecord, ObservedPrincipal, ObservedEntitlement, ObservedGrant, ObservedCredential, ConnectorHealth.

### Immutable evidence/result

ApprovalDecision, ReviewDecision, RiskAssessment, SoDConflict, PolicyEvaluation, ProvisioningAttempt, AuditRecord, EvidenceSnapshot.

### Projection

EffectiveAccess, DesiredPrincipalState, DesiredGrantState, AssignmentFulfillment, Identity360, Application360, effective administrative access, and search/reporting views.
