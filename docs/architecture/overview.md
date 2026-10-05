# IAM v2 Architecture Overview

## Architectural objective

Wyrmgate IAM is an enterprise **Identity and Access Management platform with integrated Identity Provider (IdP), Single Sign-On (SSO), and Identity Governance and Administration (IGA)** capabilities. It can authenticate governed principals itself, federate external authentication providers, issue standards-based OIDC/OAuth tokens to registered applications, and govern identity/access lifecycles end to end.

The architecture separates authentication, runtime authorization, business governance intent, technical realization, and external provider observation so that SSO, policy, approval, review, provisioning, reconciliation, credentials, and audit remain explainable and independently evolvable.

The architecture is framework-neutral. Java/Spring, database technology, OAuth/OIDC implementation libraries, message transport, connector runtime, and deployment topology are implementation mappings rather than domain definitions.

## Canonical capabilities

The logical capabilities remain:

- **Identity** — canonical Identity, typed profiles, organizations/relationships, SourceSystem/SourceRecord/IdentityLink, Principal, lifecycle, merge/split.
- **Catalog** — Application, ApplicationTarget, Entitlement, Role, RoleVersion, environment and catalog governance metadata, including governed application registration inputs used by SSO protocol adapters.
- **Access** — AccessAssignment plus EffectiveAccess and desired technical-state projections.
- **Governance** — requests, approvals, reviews, Policy/PolicyVersion, SoD/risk evaluation orchestration, GovernanceException, GovernanceFinding and simulation.
- **Credential** — durable credential/authenticator governance, credential lifecycle, consumer bindings and rotation intent.
- **Integration** — ConnectorInstance/Binding, connector capabilities, provisioning, reconciliation, external authentication/federation adapters, and provider-observed state.
- **Administration** — IAM control-plane permissions, roles, grants, scopes, delegation, elevation and break-glass authorization, including authority to configure IdP/SSO surfaces.
- **Audit** — append-only AuditRecord, EvidenceSnapshot, search/export/archive semantics.

Platform supports those capabilities with replaceable implementations such as protocol endpoints, browser session handling, token issuance, signing-key infrastructure, secret/verifier providers, clocks, ID generation, notifications, schedulers, reliable event publication and persistence.

**IdP/SSO is a product service composed from these capability owners, not a ninth generic business aggregate.** Protocol/session/token infrastructure must not take ownership of Identity, Principal, Credential, Application, AccessAssignment or AdministrativeGrant state merely because an OAuth/OIDC library persists technical records.

## First-party authentication and SSO

Under ADR-0042 Wyrmgate may act as its own OpenID Provider / OAuth authorization server.

The baseline product contract includes:

- first-party local authentication of governed Principals using Credential-owned authenticator semantics;
- optional federation from external IdPs through Integration/adapters;
- browser SSO sessions;
- OIDC discovery and public JWKS;
- Authorization Code with PKCE;
- signed ID/access tokens;
- exact registered redirect URI validation;
- governed application/client configuration;
- explicit claim release and data minimization;
- bounded session/token validity, revocation and signing-key rotation.

OAuth scopes and token claims are protocol projections. They are not Wyrmgate AdministrativePermission and do not replace Access/Governance state.

## Ownership of truth

Only the owning capability may mutate its authoritative state. Cross-capability collaboration uses semantic queries/commands/events; it must not rely on foreign repositories or shared persistence entities.

Examples:

- Governance may authorize access, but Access creates/revokes `AccessAssignment`.
- Integration may observe excess provider access, but it does not silently create an `AccessAssignment`.
- Review records a decision, while remediation invokes current Access state rather than mutating a snapshot.
- Credential governs authenticator/credential state while a protocol adapter verifies or exercises it through a typed port.
- Catalog governs Application semantics while an OIDC adapter keeps protocol-specific client/session data behind its boundary.
- An upstream IdP can authenticate a subject, but its groups/scopes do not mutate Wyrmgate Administration or Access authority.

## State categories

IAM keeps four categories separate:

1. **Authoritative state** — IAM-governed truth such as Identity, RoleVersion and AccessAssignment.
2. **Observation** — source/provider facts such as SourceRecord or ObservedGrant.
3. **Evidence** — immutable decision/execution context such as ApprovalDecision or EvidenceSnapshot.
4. **Projection** — derived/rebuildable state such as EffectiveAccess, Identity360, desired technical state, token claims, or effective administrative access.

These categories must not be collapsed into one generic persistence/object model. Protocol session/code/token records are security-sensitive technical state and are not automatically domain authority or public evidence.

## Three authorization planes

IAM distinguishes:

- **Governed access plane** — Identity → AccessAssignment → EffectiveAccess → Principal → target/application.
- **IAM control plane** — authenticated governed actor → Administration authorization → IAM command/state change.
- **Runtime authorization plane** — authenticated principal/client → relying-party/runtime resource authorization.

Administrative roles are not IAM business/application roles, administrative grants are not access assignments, and OAuth scopes are not administrative permissions.

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

For SSO, the equivalent read path is intentionally non-authoritative:

```text
Identity / Principal / Credential + governed application/access context
        ↓ semantic queries
Authentication + SSO protocol service
        ↓ curated projection
Session / ID token / access token claims
```

Token issuance never writes back inferred business or administrative authority.

## Deployment evolution

The initial implementation may be a modular monolith, but logical capability boundaries do not imply one deployment topology forever. A future capability or protocol edge may move to a separate process only when operational/scaling/security needs justify it; doing so must not redefine its domain ownership semantics.
