# IAM v2 Architecture Overview

## Architectural objective

Wyrmgate IAM is a full identity governance and administration platform. The architecture separates business governance intent from technical realization and external provider observation so that policy, approval, review, provisioning, reconciliation, credentials, and audit remain explainable and independently evolvable.

The architecture is framework-neutral. Java/Spring, database technology, message transport, connector runtime, and deployment topology are implementation mappings rather than domain definitions.

## Canonical capabilities

The logical capabilities are:

- **Identity** — canonical Identity, typed profiles, organizations/relationships, SourceSystem/SourceRecord/IdentityLink, Principal, lifecycle, merge/split.
- **Catalog** — Application, ApplicationTarget, Entitlement, Role, RoleVersion, environment and catalog governance metadata.
- **Access** — AccessAssignment plus EffectiveAccess and desired technical-state projections.
- **Governance** — requests, approvals, reviews, Policy/PolicyVersion, SoD/risk evaluation orchestration, GovernanceException, GovernanceFinding and simulation.
- **Credential** — durable credential governance, credential lifecycle, consumer bindings and rotation intent.
- **Integration** — ConnectorInstance/Binding, connector capabilities, provisioning, reconciliation and provider-observed state.
- **Administration** — IAM control-plane permissions, roles, grants, scopes, delegation, elevation and break-glass authorization.
- **Audit** — append-only AuditRecord, EvidenceSnapshot, search/export/archive semantics.

Supporting capabilities such as authentication, secret providers, clocks, ID generation, notifications, schedulers, reliable event publication and persistence are replaceable implementations behind semantic contracts.

## Ownership of truth

Only the owning capability may mutate its authoritative state. Cross-capability collaboration uses semantic queries/commands/events; it must not rely on foreign repositories or shared persistence entities.

Examples:

- Governance may authorize access, but Access creates/revokes `AccessAssignment`.
- Integration may observe excess provider access, but it does not silently create an `AccessAssignment`.
- Review records a decision, while remediation invokes current Access state rather than mutating a snapshot.
- Credential governs credential state while Integration performs external provider operations.

## State categories

IAM keeps four categories separate:

1. **Authoritative state** — IAM-governed truth such as Identity, RoleVersion and AccessAssignment.
2. **Observation** — source/provider facts such as SourceRecord or ObservedGrant.
3. **Evidence** — immutable decision/execution context such as ApprovalDecision or EvidenceSnapshot.
4. **Projection** — derived/rebuildable state such as EffectiveAccess, Identity360 or desired technical state.

These categories must not be collapsed into one generic persistence/object model.

## Three authorization planes

IAM distinguishes:

- **Governed access plane** — Identity → AccessAssignment → EffectiveAccess → Principal → target.
- **IAM control plane** — actor → administrative authorization → IAM command/state change.
- **Runtime authorization plane** — authenticated principal/client → runtime resource authorization.

Administrative roles are not IAM business/application roles, and administrative grants are not access assignments.

## Desired-state convergence

The technical convergence chain is:

```text
AccessAssignment / identity facts
        ↓
EffectiveAccess
        ↓
DesiredPrincipalState / DesiredGrantState / credential requirements
        ↓
Provisioning plan + tasks
        ↓
External provider
        ↓
Observed state
        ↓
Reconciliation
        ↓
Drift / GovernanceFinding / remediation
```

Provider failures never rewrite a valid governance decision. Stale work is superseded after desired-state revision revalidation.

## Deployment evolution

The initial implementation may be a modular monolith, but logical capability boundaries do not imply one deployment topology forever. A future capability may move to a separate process only when operational/scaling/security needs justify it; doing so must not redefine its domain ownership semantics.
