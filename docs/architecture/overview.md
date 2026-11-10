# Wyrmgate IAM Architecture Overview

## Architectural objective

Wyrmgate IAM is an enterprise **Identity and Access Management platform with integrated Identity Provider (IdP), Single Sign-On (SSO), and Identity Governance and Administration (IGA)** capabilities. It can authenticate governed principals itself, federate external authentication providers, issue standards-based OIDC/OAuth tokens to registered applications, and govern identity/access lifecycles end to end.

The architecture separates tenant lifecycle/isolation, authentication, runtime authorization, business governance intent, technical realization, provider observation and evidence so that each remains explainable and independently evolvable.

The architecture is framework-neutral. Java/Spring, database technology, OAuth/OIDC libraries, message transport, connector runtime and deployment topology are implementation mappings rather than domain definitions.

See [`layered-architecture.md`](layered-architecture.md) for the reconciled Layer 1 through Layer 6 model.

## Canonical capabilities

The canonical Layer-4 business capabilities are:

- **Tenancy** — deployment-owned Tenant identity/lifecycle and trusted tenant/deployment operation-scope boundary.
- **Identity** — canonical Identity, typed profiles, organizations/relationships, SourceSystem/SourceRecord/IdentityLink, Principal, lifecycle and merge/split.
- **Catalog** — Application, ApplicationTarget, Entitlement, Role/RoleVersion, governed SSO relying-party registration and deployment-owned platform application offerings/bindings.
- **Access** — AccessAssignment authority plus EffectiveAccess and desired technical-state projections, IdentityAccessReduction and AccessTargetReduction.
- **Governance** — requests, approvals, reviews, Policy/PolicyVersion, SoD/risk evaluation, GovernanceException and GovernanceFinding.
- **Credential** — durable credential/authenticator governance, lifecycle, secret-reference boundary and rotation intent/process.
- **Integration** — ConnectorInstance/Binding, provisioning/reconciliation process semantics, provider observations, EntitlementObservationMapping and PrincipalObservationMapping.
- **Administration** — IAM control-plane permissions, roles, grants, scopes, TENANT_OWNER/PLATFORM_OWNER authority, delegation, elevation and break-glass.
- **Audit** — immutable Audit evidence and evidence lifecycle with explicit TENANT/DEPLOYMENT evidence ownership.

Platform supports those capabilities with replaceable technical mechanics such as persistence/transactions, reliable delivery, scheduler/leases/retry, projections/read infrastructure, protocol/session state, cryptography/signing, secret/configuration runtime, telemetry and deployment/recovery infrastructure.

**Authentication/IdP/SSO is a composed product service across the layers and capability owners, not an additional Layer-4 business capability.** Protocol/session/token infrastructure must not take ownership of Tenant, Identity, Principal, Credential, Application, AccessAssignment or AdministrativeGrant state merely because an OAuth/OIDC framework persists technical records.

## Tenancy and operation scope

ADR-0051 makes Tenancy a first-class capability. Tenant is the hard customer/security isolation realm; Organization remains business structure inside a Tenant.

Authoritative resources are explicitly `TENANT_OWNED` or `DEPLOYMENT_OWNED`. Application/runtime dispatch uses trusted server-derived operation scope:

```text
TENANT(TenantId)
DEPLOYMENT
```

Browser headers, route parameters, claims or UI tenant selectors never establish authoritative scope by themselves.

Tenant lifecycle is:

```text
PROVISIONING -> ACTIVE <-> SUSPENDED -> DECOMMISSIONING -> DECOMMISSIONED
```

Tenancy owns this lifecycle but does not own tenant-local capability state or Administration owner authority. Suspension/decommissioning never blocks authoritative privilege reduction, credential disabling, provider deprovisioning, investigation or Audit evidence work.

## First-party authentication and SSO

Under ADR-0042 Wyrmgate may act as its own OpenID Provider / OAuth authorization server.

The current baseline includes first-party Principal authentication through Credential, optional upstream federation through adapters, browser SSO sessions, OIDC discovery/JWKS, Authorization Code with mandatory S256 PKCE, signed ID/access tokens, exact redirect-URI validation, governed Catalog client registration and data-minimized claim release.

OAuth scopes and token claims are protocol projections. They are not AdministrativePermission and do not replace Access/Governance state.

SAML protocol support and inbound SCIM APIs remain next-phase Layer-2 surfaces unless separately promoted; provider-side adapters may nevertheless speak SAML/SCIM behind Layer 5 without making them public current-baseline protocols.

## Ownership of truth

Only the owning capability may mutate its authoritative state. Cross-capability collaboration uses semantic queries, commands and facts; it never relies on foreign repository/table mutation.

Examples:

- Tenancy changes Tenant lifecycle; each affected capability performs its own shutdown/reduction work.
- Governance may authorize access, but Access creates or terminates `AccessAssignment`.
- Catalog decides target availability, but Access owns `AccessTargetReduction` and assignment termination under ADR-0052.
- Integration may correlate a provider account through `PrincipalObservationMapping`, but Identity remains the sole owner of `Principal` authority under ADR-0050.
- Provider observation never silently creates Catalog or Access authority.
- Credential governs authenticator metadata while authentication consumes it through a typed verifier boundary.
- Audit records immutable semantic evidence after owner outcomes; Audit failure never rewrites the originating business/security decision.

## State categories

IAM keeps four categories separate:

1. **Authoritative state** — IAM-governed truth such as Tenant, Identity, RoleVersion and AccessAssignment.
2. **Observation** — source/provider facts such as SourceRecord or ObservedGrant.
3. **Evidence** — immutable decision/execution context such as ApprovalDecision, AuditRecord or EvidenceSnapshot.
4. **Projection** — derived/rebuildable state such as EffectiveAccess, Identity360, desired technical state, token claims or effective administrative access.

These categories must not be collapsed into one generic persistence/object model. Protocol session/code/token records are security-sensitive technical state, not domain authority or public evidence.

## Three authorization planes

IAM distinguishes:

- **Governed access plane** — Identity -> AccessAssignment -> EffectiveAccess -> Principal -> target/application.
- **IAM control plane** — authenticated governed actor -> Administration authorization -> semantic IAM operation.
- **Runtime authorization plane** — authenticated principal/client -> relying-party/runtime resource authorization.

Administrative roles are not business/application Roles, administrative grants are not access assignments, and OAuth scopes are not administrative permissions.

## Desired-state convergence

```text
Identity/Catalog/Tenancy facts + AccessAssignment
        ↓
current semantic eligibility/target availability
        ↓
EffectiveAccess
        ↓
DesiredPrincipalState / DesiredGrantState / credential requirements
        ↓
Integration plan + durable tasks
        ↓
External provider
        ↓
Observed state
        ↓
Reconciliation
        ↓
Drift / GovernanceFinding / remediation
```

Identity eligibility loss and terminal Catalog target unavailability suppress semantic access immediately and drive Access-owned durable reduction. Provider failures never rewrite an authoritative decision; stale async work re-reads current state and is superseded/no-oped.

## Audit evidence ownership

ADR-0053 defines:

```text
AuditEvidenceScope = TENANT(ownerTenantId) | DEPLOYMENT
```

Deployment evidence is explicit and is not modeled as a tenant-owned record with a null owner. Actor home-Tenant and affected-Tenant references are provenance, not evidence ownership or authorization shortcuts. Platform authority never implies unrestricted tenant Audit visibility.

## Deployment evolution

The implementation may remain a modular monolith and later extract selected runtime/protocol/worker components only for concrete operational, security or scaling reasons. Capability boundaries do not imply microservices, schemas or deployment processes.

Layer 6 owns technical mechanics only. Runtime roles such as API, WORKER, SCHEDULER, CONNECTOR_WORKER or ALL never become business capabilities and must preserve capability ownership, at-least-once/replay-safe behavior, secret boundaries and accepted HA/DR objectives.
