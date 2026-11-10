# Canonical IAM Domain Model

## Purpose

This document defines the canonical concepts used throughout Wyrmgate IAM independently of framework or persistence technology.

## Tenancy

`Tenant` is the deployment-owned authoritative isolation/customer realm. Tenancy owns Tenant identity, canonical display metadata, lifecycle and revision; it does not own tenant-local capability state or Administration owner authority.

Tenant lifecycle is `PROVISIONING -> ACTIVE <-> SUSPENDED -> DECOMMISSIONING -> DECOMMISSIONED`; `DECOMMISSIONED` is terminal. `ACTIVE` requires a viable Administration-owned `TENANT_OWNER`. Suspension/decommissioning never blocks authoritative security reduction, deprovisioning, investigation or Audit evidence work.

Application/runtime scope is explicit and server-derived: `TENANT(TenantId)` or `DEPLOYMENT`. Browser/UI selectors and untrusted claims are never authoritative scope.

Every authoritative aggregate is explicitly tenant-owned or deployment-owned. Deployment ownership is not represented as a nullable Tenant owner on an ordinary tenant-owned aggregate.

## Identity

`Identity` is the canonical governed subject. Top-level types are `PERSON`, `SERVICE`, and `WORKLOAD`. Identity type is stable for normal operations and determines exactly one compatible typed profile.

A canonical Identity may be represented by many `Principal` objects in external targets. `Principal` is a technical representation, not the governed subject itself.

Identity-source construction uses `SourceSystem`, `SourceRecord`, `IdentityLink`, attribute mapping, correlation and attribute-authority rules. Mapping, correlation and authority are separate concerns. Source records preserve what external sources reported and never become canonical state merely because they were imported.

ADR-0022 adds the first positive source-driven construction policy. `SourceCorrelationPolicyVersion` is Identity-owned and immutable after activation. The first implementation uses one exact `STRING`/`SINGLE` governed canonical key, keeps an existing accepted `IdentityLink` sticky, refuses ambiguous matches, and may create a new Identity only when policy explicitly permits no-match creation. A source-created Identity begins `PENDING`, so source arrival alone never creates access-eligible authority. Historical source-derived candidates remain provenance after explicit relinking but are current resolution inputs only while their SourceRecord is actively accepted to that Identity. Source lifecycle authority is provenance-fenced: trusted absence and explicit source suspension retain distinct immutable transition evidence, and only exact current revision/current accepted-source relationships may use the narrow ADR-0027/ADR-0038 restoration paths.

Lifecycle-access policy consumes governed canonical values only through Identity semantic queries. ADR-0039 extends exact SINGLE-value equality to normalized DECIMAL, civil DATE and absolute microsecond-precision DATETIME; ADR-0040 adds typed MULTI one-value CONTAINS; ADR-0041 adds bounded Access-owned expected sets for CONTAINS_ANY/CONTAINS_ALL. Collection order and duplicate count are not business authority.

## Organization and relationships

`Organization` is business/governance structure inside a Tenant, not tenant isolation. Identity-to-organization relationships are typed and temporal; a single `organizationId` extension attribute is insufficient to model legal employer, business unit, department, project or other first-class relationships.

Manager and ownership relationships preserve provenance. Non-human identities require accountable ownership according to policy. Ordinary subject/manager/organization/ownership relationships never cross Tenant boundaries.

## Application catalog

`Application` is a tenant-owned governable business application or capability. `ApplicationTarget` is a technical target/environment belonging to exactly one Application. A single connector may serve multiple targets through explicit Integration-owned `ConnectorBinding` objects.

`Entitlement` is the smallest governable technical access unit. It belongs to one Application and optionally one compatible ApplicationTarget.

IAM roles are business Catalog constructs:

- `BUSINESS` Role — organizational/job-function access package that may span applications.
- `APPLICATION` Role — business-facing access package within one Application.

Role composition is versioned through immutable `RoleVersion` objects. Initial composition is constrained to BUSINESS -> APPLICATION Role, BUSINESS -> Entitlement, and APPLICATION -> Entitlement.

ADR-0049 adds deployment-owned `PlatformApplicationOffering`, typed admission state and `PlatformApplicationBinding` without turning tenant-owned Applications into cross-Tenant objects.

ADR-0052 adds Catalog semantic access-target availability: `ASSIGNABLE`, `TEMPORARILY_UNAVAILABLE`, or `TERMINALLY_UNAVAILABLE`. Terminal target unavailability is Catalog truth; it never authorizes Catalog to mutate AccessAssignment.

## Access intent and realization

`AccessAssignment` is authoritative business access intent. It always belongs to one Identity and targets exactly one Role or Entitlement. It may constrain technical realization to `ANY`, reserved typed principal profiles, or a `SPECIFIC` Principal according to accepted implementation contracts.

Multiple assignments may legitimately support the same effective access because they preserve distinct causes such as lifecycle policy and approved request.

`EffectiveAccess` is not an aggregate; it is a rebuildable projection derived from current assignments, Identity eligibility and current Catalog semantics while retaining supporting provenance paths.

`DesiredPrincipalState` and `DesiredGrantState` are Access-owned technical desired-state projections compared with Integration provider observation.

`IdentityAccessReduction` is Access-owned durable process state for Identity eligibility loss. ADR-0052 adds `AccessTargetReduction` as Access-owned durable process state for terminal Catalog target unavailability. In both cases semantic access disappears before asynchronous cleanup completes; foreign capabilities never mutate AccessAssignment directly.

Temporary Catalog unavailability may suppress current role-derived EffectiveAccess while invalid but is deliberately non-destructive. Terminal target unavailability causes bounded current-state assignment termination using ordinary CANCELLED/EXPIRED/REVOKED semantics.

## Governance

Governance owns requests, approvals, reviews, policy/versioning, SoD/risk evaluation orchestration, exceptions and findings.

`AccessRequest` has one beneficiary and one or more `RequestItem` objects. Reusable Governance-owned `ApprovalCase` records bind a typed subject reference to an immutable `ApprovalPlan` made of ordered stages and resolved approver snapshots. `ApprovalDecision` and `ReviewDecision` are immutable evidence. Approval owns decision orchestration only; the approved subject retains business-state ownership.

The first review runtime is `IDENTITY_ACCESS`. `ReviewCampaign` owns durable generation state for one governed subject Identity, one explicit reviewer Identity and one fixed snapshot cutoff. `ReviewItem` is the scalable consistency boundary and snapshots one authoritative AccessAssignment reference plus target/principal/provenance/validity context read during bounded generation. `ReviewDecision` is immutable `KEEP`/`REVOKE` evidence. `ReviewRemediation` is separate process state; `REVOKE` invokes an Access-owned current-state remediation command and never treats the historical item snapshot as mutation authority.

`GovernanceException` is explicit Governance authority, not a flag on a violation. The first runtime scope is one governed Identity + one exact immutable `SoDRule`; every exception has a required business reason, reusable ApprovalCase, validity window and optional predecessor for renewal. An approved exception affects only that exact rule while temporally effective, never carries automatically to a successor PolicyVersion, and never removes the underlying `SoDConflict`/risk evidence. `GovernanceFinding` remains the common actionable issue lifecycle for drift, missing ownership, stale access, credential issues and policy violations.

`Policy` is stable Governance authority and `PolicyVersion` is immutable evaluated content after activation. The implemented first runtime purpose is `ACCESS_REQUEST`. ADR-0049 GOVERNED_POLICY platform-application admission is a separate typed integration point and must not silently reuse ACCESS_REQUEST policy semantics; its concrete Governance policy purpose/contract remains separately accepted work.

## Credential

`Credential` is Credential-owned authoritative authentication-instrument metadata belonging to exactly one Identity-owned Principal by stable ID. Supported typed kinds include PASSWORD/API_KEY/SSH_KEY/CERTIFICATE/OAUTH_CLIENT_SECRET metadata while only opaque external `SecretReference(providerType, referenceKey)` is retained; no raw secret/private material belongs in the Credential domain or ordinary persistence.

Credential lifecycle is SCHEDULED/ACTIVE/REVOKED/EXPIRED/COMPROMISED with semantic time effectiveness evaluated directly. `CredentialRotation` is a durable Credential-owned process rather than a `ROTATING` credential status because old and replacement credentials can coexist during cutover.

Credential private material remains behind typed verifier/SecretProvider boundaries. First-party Authentication/IdP consumes bounded Credential proofs; it does not own Credential authority or persist raw material.

## Integration and observation

`ConnectorInstance` is configured Integration state; `ConnectorBinding` binds a connector capability to an Identity-owned SourceSystem or Catalog-owned ApplicationTarget by stable reference.

Integration owns `ProvisioningJob`, `ProvisioningTask`, immutable `ProvisioningAttempt`, `ReconciliationRun`, and provider observations such as `ObservedPrincipal`, `ObservedEntitlement`, `ObservedGrant`, and `ObservedCredential`.

Observed state never silently overwrites governed desired state.

ADR-0015 defines Integration-owned `EntitlementObservationMapping`. ADR-0050 adds Integration-owned `PrincipalObservationMapping`: one binding-scoped stable provider principal identifier may resolve to one existing Identity-owned Principal after exact same-Tenant/same-ApplicationTarget validation. Integration never creates, reassigns or mutates Principal authority, and mutable provider strings such as username/email/display name are not fallback correlation authority.

## Administration

`AdministrativeRole`, `AdministrativePermission`, `AdministrativeGrant`, `AdministrativeScope`, `AdministrativeDelegation`, `AdministrativeElevation`, `AdministrativeBreakGlassOperation`, `TENANT_OWNER` and `PLATFORM_OWNER` govern permission to operate IAM itself. These remain distinct from normal Catalog Roles, Entitlements, AccessAssignments and Governance approval evidence.

A direct AdministrativeGrant is standing authority and explicitly records whether its bounded authority is grantable and/or delegable. Delegation, elevation and break-glass preserve their distinct provenance and dependency/process invariants.

Tenancy asks Administration through typed viability/owner-continuity guards; Tenancy never writes Administration authority. Platform Owner authority does not automatically grant unrestricted tenant-owned Identity/Access/Governance/Audit inspection.

## Audit and evidence

`DomainEvent`, `AuditRecord`, `EvidenceSnapshot`, and technical operational logging have different semantics:

- Domain events drive internal reactions.
- Audit records provide immutable governed/security evidence.
- Evidence snapshots capture decision-time context.
- Operational logs diagnose execution.

IAM is event-driven where useful but does not require event sourcing as its authoritative persistence model.

ADR-0031 implements the Audit-owned runtime foundation. ADR-0034 through ADR-0037 add durable export/archive, legal hold and dual-control purge, immutable EvidenceSnapshot/material/integrity evidence, and separate SIEM delivery.

ADR-0053 makes Audit evidence ownership explicit:

```text
AuditEvidenceScope = TENANT(ownerTenantId) | DEPLOYMENT
```

Deployment evidence is not modeled as a tenant-owned row with nullable ownership. Actor home-Tenant and an optional affected-Tenant reference are provenance, not evidence ownership or authorization shortcuts. Evidence export/archive/retention/hold/purge processes remain within one evidence realm. Platform authority does not automatically grant tenant Audit visibility.

## Canonical classification

### Authoritative/stateful

Tenant; Identity, Organization, IdentityLink, Principal; Application, ApplicationTarget, Entitlement, Role/RoleVersion, PlatformApplicationOffering/admission/binding; AccessAssignment and durable Access reduction process state; request/review workflow state, Policy/PolicyVersion, SoDRule, GovernanceException, GovernanceFinding; Credential/CredentialRotation; ConnectorInstance/Binding, observation mappings and provisioning/reconciliation process state; AdministrativeRole/Grant/Delegation/Elevation/BreakGlass/owner authority; Audit evidence-lifecycle process/control state.

### Observation

SourceRecord, ObservedPrincipal, ObservedEntitlement, ObservedGrant, ObservedCredential, ConnectorHealth.

### Immutable evidence/result

ApprovalDecision, ReviewDecision, RiskAssessment, SoDConflict, PolicyEvaluation, ProvisioningAttempt, AuditRecord, EvidenceSnapshot and other closed material/integrity evidence.

### Projection

EffectiveAccess, DesiredPrincipalState, DesiredGrantState, AssignmentFulfillment, Identity360, Application360, effective administrative access, archived-record/query indexes, search/reporting views and protocol claim projections.

## Administrative break-glass

AdministrativeBreakGlassOperation is a first-class Administration-owned emergency authority/process object, distinct from direct AdministrativeGrant, AdministrativeDelegation and AdministrativeElevation. It records the exact governed actor/beneficiary, requested AdministrativeRole and typed scope, finite validity, reason/incident evidence, provider-neutral activation assurance, lifecycle/revision, correlation/causation and durable obligation references.

Break-glass authority contributes to control-plane authorization only while ACTIVE, semantically time-valid, the governed actor remains administratively eligible and current STRONG assurance satisfies the policy-bounded assurance-age requirement. SECURITY_NOTIFICATION and POST_USE_REVIEW are durable obligations/evidence, not authority. AuditRecord remains separate Audit-owned data-minimized evidence.

## Audit evidence lifecycle completion

Audit concepts that must not be collapsed include:

- `AuditRecord` — immutable semantic occurrence evidence;
- `EvidenceSnapshot` — immutable decision-time context, never current-state authority;
- `AuditArchiveSegment` / archived-record index — immutable external artifact plus query projection;
- `AuditLegalHold` / `AuditPurgeOperation` — explicit evidence-lifecycle control/process state;
- `AuditEvidenceScope` — explicit TENANT or DEPLOYMENT ownership realm.

SIEM delivery is a separate outbound technical transport of data-minimized Audit evidence. It is neither a public integration event nor authoritative business state.
