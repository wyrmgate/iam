# Canonical IAM Domain Model

## Purpose

This document defines the canonical concepts used throughout IAM v2 independently of framework or persistence technology.

## Identity

`Identity` is the canonical governed subject. Top-level types are `PERSON`, `SERVICE`, and `WORKLOAD`. Identity type is stable for normal operations and determines exactly one compatible typed profile.

A canonical Identity may be represented by many `Principal` objects in external targets. `Principal` is a technical representation, not the governed subject itself.

Identity-source construction uses `SourceSystem`, `SourceRecord`, `IdentityLink`, attribute mapping, correlation and attribute-authority rules. Mapping, correlation and authority are separate concerns. Source records preserve what external sources reported and never become canonical state merely because they were imported.

ADR-0022 adds the first positive source-driven construction policy. `SourceCorrelationPolicyVersion` is Identity-owned and immutable after activation. The first implementation uses one exact `STRING`/`SINGLE` governed canonical key, keeps an existing accepted `IdentityLink` sticky, refuses ambiguous matches, and may create a new Identity only when policy explicitly permits no-match creation. A source-created Identity begins `PENDING`, so source arrival alone never creates access-eligible authority. Historical source-derived candidates remain provenance after explicit relinking but are current resolution inputs only while their SourceRecord is actively accepted to that Identity.

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

`AccessRequest` has one beneficiary and one or more `RequestItem` objects. Reusable Governance-owned `ApprovalCase` records bind a typed subject reference to an immutable `ApprovalPlan` made of ordered stages and resolved approver snapshots. `ApprovalDecision` and `ReviewDecision` are immutable evidence. Approval owns decision orchestration only; the approved subject retains business-state ownership.

The first review runtime is `IDENTITY_ACCESS`. `ReviewCampaign` owns durable generation state for one governed subject Identity, one explicit reviewer Identity and one fixed snapshot cutoff. `ReviewItem` is the scalable consistency boundary and snapshots one authoritative AccessAssignment reference plus target/principal/provenance/validity context read during bounded generation. `ReviewDecision` is immutable `KEEP`/`REVOKE` evidence. `ReviewRemediation` is separate process state; `REVOKE` invokes an Access-owned current-state remediation command and never treats the historical item snapshot as mutation authority.

`GovernanceException` is explicit Governance authority, not a flag on a violation. The first runtime scope is one governed Identity + one exact immutable `SoDRule`; every exception has a required business reason, reusable ApprovalCase, validity window and optional predecessor for renewal. An approved exception affects only that exact rule while temporally effective, never carries automatically to a successor PolicyVersion, and never removes the underlying `SoDConflict`/risk evidence. `GovernanceFinding` remains the common actionable issue lifecycle for drift, missing ownership, stale access, credential issues and policy violations.

`Policy` is stable Governance authority and `PolicyVersion` is immutable evaluated content after activation. The first runtime purpose is `ACCESS_REQUEST`: a typed default decision, symmetric Entitlement-pair `SoDRule` content and, where required, immutable typed approval stages with resolved governed Identity approvers. `RiskAssessment` explains severity/factors; `PolicyEvaluation` records the decision; `SoDConflict` records immutable matched-rule evidence. Policy decides what IAM does about risk. `GovernanceException` remains a separate explicit deviation lifecycle and never deletes the underlying conflict or finding.

## Credential

`Credential` is Credential-owned authoritative authentication-instrument metadata belonging to exactly one Identity-owned Principal by stable ID. The first runtime supports typed PASSWORD/API_KEY/SSH_KEY/CERTIFICATE/OAUTH_CLIENT_SECRET metadata and stores only an opaque external `SecretReference(providerType, referenceKey)`; no raw secret/private material exists in the Credential domain or persistence model. Credential lifecycle is SCHEDULED/ACTIVE/REVOKED/EXPIRED/COMPROMISED, with temporal effectiveness evaluated directly from lifecycle plus validity.

`CredentialRotation` is a durable Credential-owned process rather than a `ROTATING` credential status because old and replacement credentials can coexist during cutover. The first runtime persists PLANNED → CREATING_REPLACEMENT → DISTRIBUTING → VERIFYING → CUTOVER_COMPLETE → REVOKING_OLD → COMPLETED plus explicit failure exits, requires the replacement to belong to the same Principal, and keeps provider/SecretProvider execution deferred behind future typed ports. `CredentialBinding` remains canonical but its concrete provider/consumer relationship is intentionally deferred because v0.3 does not define its fields.

## Integration and observation

`ConnectorInstance` is configured integration state; `ConnectorBinding` binds a connector capability to a SourceSystem or ApplicationTarget.

Integration owns `ProvisioningJob`, `ProvisioningTask`, immutable `ProvisioningAttempt`, `ReconciliationRun`, and provider observations such as `ObservedPrincipal`, `ObservedEntitlement`, `ObservedGrant`, and `ObservedCredential`.

Observed state never silently overwrites governed desired state.

## Administration

`AdministrativeRole`, `AdministrativePermission`, `AdministrativeGrant`, `AdministrativeScope`, and `AdministrativeDelegation` govern permission to operate IAM itself. ADR-0032 additionally defines `AdministrativeElevation` and `AdministrativeBreakGlassOperation` as distinct Administration-owned temporary/emergency authority processes. These concepts remain distinct from normal IAM roles, entitlements, access assignments and Governance approval evidence.

A direct AdministrativeGrant is standing authority and explicitly records whether its bounded authority is grantable and/or delegable. `administration:manage-authorization` authorizes management operations but does not itself create a grantability ceiling. The first delegation model is single-hop and references one direct source grant; it remains effective only while that source still contains the delegated role/scope/time authority. Temporary elevation uses one current grantable direct basis and revalidates current authority/approval context before activation. Break-glass is never inferred from a GLOBAL grant and requires strong provider-neutral assurance, short finite validity, reason/incident evidence, durable notification work and post-use review.

## Audit and evidence

`DomainEvent`, `AuditRecord`, `EvidenceSnapshot`, and technical operational logging have different semantics:

- Domain events drive internal reactions.
- Audit records provide immutable governed/security evidence.
- Evidence snapshots capture decision-time context.
- Operational logs diagnose execution.

IAM is event-driven where useful but does not require event sourcing as its authoritative persistence model.

ADR-0031 implements the first Audit-owned runtime foundation. `AuditRecord` is immutable append-only evidence with stable record identity, occurrence/recording time, optional governed actor, semantic action/resource reference, normalized `SUCCESS`/`DENIED`/`FAILURE` outcome and correlation/causation. Producer replay is idempotent by stable record ID plus semantic content; conflicting reuse is rejected. Bounded Audit queries order by `occurredAt DESC, id DESC`. EvidenceSnapshot, material snapshots, archive/retention and public Audit APIs remain deferred.

## Canonical classification

### Authoritative/stateful

Identity, Organization, IdentityLink, Principal, Application, ApplicationTarget, Entitlement, Role/RoleVersion, AccessAssignment, request/review workflow state, Policy/PolicyVersion, SoDRule, GovernanceException, GovernanceFinding, Credential, ConnectorInstance/Binding, provisioning/reconciliation state, AdministrativeRole/Grant/Delegation.

### Observation

SourceRecord, ObservedPrincipal, ObservedEntitlement, ObservedGrant, ObservedCredential, ConnectorHealth.

### Immutable evidence/result

ApprovalDecision, ReviewDecision, RiskAssessment, SoDConflict, PolicyEvaluation, ProvisioningAttempt, AuditRecord, EvidenceSnapshot.

### Projection

EffectiveAccess, DesiredPrincipalState, DesiredGrantState, AssignmentFulfillment, Identity360, Application360, effective administrative access, and search/reporting views.
