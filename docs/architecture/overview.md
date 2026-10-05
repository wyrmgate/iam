# IAM v2 Architecture Overview

## Architectural objective

Wyrmgate IAM is a full enterprise Identity and Access Management platform. It combines Identity Governance and Administration with first-party Authentication, Identity Provider and Single Sign-On capability. The architecture separates identity, authentication, business access/governance intent, technical realization, external provider observation, control-plane authorization and audit so each remains explainable and independently evolvable.

The architecture is framework-neutral. Java/Spring, database technology, message transport, OAuth/OIDC libraries, connector runtime, and deployment topology are implementation mappings rather than domain definitions.

## Canonical capabilities

The logical capabilities are:

- **Identity** — canonical Identity, typed profiles, organizations/relationships, SourceSystem/SourceRecord/IdentityLink, Principal, lifecycle, merge/split.
- **Catalog** — Application, ApplicationTarget, Entitlement, Role, RoleVersion, environment and catalog governance metadata.
- **Access** — AccessAssignment plus EffectiveAccess and desired technical-state projections.
- **Governance** — requests, approvals, reviews, Policy/PolicyVersion, SoD/risk evaluation orchestration, GovernanceException, GovernanceFinding and simulation.
- **Credential** — durable credential governance, credential lifecycle, consumer bindings and rotation intent; private authenticator material remains behind secret-provider boundaries.
- **Authentication** — first-party and federated authentication, tenant-bound login bindings and sessions, OIDC/OAuth client/grant state, stable subject identifiers, SSO/federation policy, protocol authorization state and governed claim release.
- **Integration** — ConnectorInstance/Binding, connector capabilities, provisioning, reconciliation and provider-observed state.
- **Administration** — IAM control-plane permissions, roles, grants, scopes, delegation, elevation and break-glass authorization.
- **Audit** — append-only AuditRecord, EvidenceSnapshot, search/export/archive semantics.

Supporting Platform capabilities such as secret-provider adapters, clocks, ID generation, signing/HSM adapters, secure random generation, notifications, schedulers, reliable event publication, persistence and observability remain replaceable implementations behind semantic contracts.

## Ownership of truth

Only the owning capability may mutate its authoritative state. Cross-capability collaboration uses semantic queries/commands/events; it must not rely on foreign repositories or shared persistence entities.

Examples:

- Authentication resolves a governed Principal/Identity and asks Credential to verify an authenticator; it does not read Credential private material directly.
- Authentication may issue an OIDC token after successful authentication, but OAuth scopes/claims do not become Wyrmgate AdministrativePermission or AccessAssignment authority.
- Governance may authorize access, but Access creates/revokes `AccessAssignment`.
- Integration may observe excess provider access, but it does not silently create an `AccessAssignment`.
- Review records a decision, while remediation invokes current Access state rather than mutating a snapshot.
- Credential governs credential state while Integration performs external provider operations.

## State categories

IAM keeps four categories separate:

1. **Authoritative state** — IAM-governed truth such as Identity, RoleVersion, AccessAssignment, Authentication client/session/grant state and Administration authority.
2. **Observation** — source/provider facts such as SourceRecord or ObservedGrant.
3. **Evidence** — immutable decision/execution context such as ApprovalDecision or EvidenceSnapshot.
4. **Projection** — derived/rebuildable state such as EffectiveAccess, Identity360 or desired technical state.

These categories must not be collapsed into one generic persistence/object model.

## Authentication and authorization planes

Wyrmgate distinguishes:

- **Authentication / SSO plane** — governed Principal/Identity + authenticator/federation context → Authentication session → OIDC/OAuth protocol result and curated claims.
- **Governed access plane** — Identity → AccessAssignment → EffectiveAccess → Principal → target.
- **IAM control plane** — authenticated governed actor → Administration authorization → IAM command/state change.
- **Runtime authorization plane** — authenticated principal/client → runtime resource authorization.

Authentication success is not business access authority and is not Wyrmgate administrative authority. Administrative roles are not IAM business/application roles, administrative grants are not access assignments, and OAuth/OIDC scopes or provider groups/roles do not silently bridge these planes.

## First-party IdP/SSO baseline

ADR-0042 establishes Authentication as a canonical capability. The first standards-based interactive SSO target is OpenID Connect Authorization Code with PKCE. Client registration is explicit and tenant-bound; redirect URIs are exact; public clients do not require a client secret; implicit and resource-owner-password grants are not supported. OIDC subjects are opaque and stable under Authentication policy, and token claim release is curated/data-minimized.

Wyrmgate may also trust explicitly configured external IdPs. Federated authentication and first-party authentication converge only at provider-neutral authenticated-subject/assurance semantics; upstream roles, groups and token scopes never define Wyrmgate Administration authority.

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

The initial implementation may be a modular monolith, including the Authentication protocol runtime, but logical capability boundaries do not imply one deployment topology forever. A future capability may move to a separate process only when operational/scaling/security needs justify it; doing so must not redefine its domain ownership semantics.