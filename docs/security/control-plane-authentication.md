# Control-plane Authentication and Initial Administrator Bootstrap

## Purpose

This document defines the implementation-facing authentication boundary that precedes Wyrmgate Administrative Authorization. ADR-0011 established the first external bearer implementation; ADR-0042 broadens Wyrmgate into an IAM/IdP/SSO platform and supersedes the implication that an external IdP is the only canonical authentication source.

Authentication answers **which governed Principal/subject authenticated and with what provider-neutral assurance**. Administration answers **what that governed actor may do to IAM state**. They remain deliberately separate decisions.

See [`identity-provider-sso.md`](identity-provider-sso.md) for the first-party IdP/SSO security boundary.

## Supported authentication sources

Wyrmgate supports two canonical authentication-source families for its control plane:

1. **First-party Wyrmgate authentication** — a governed Principal authenticates using Credential-owned authenticator semantics and a secure server-side browser/session boundary.
2. **Federated/external authentication** — a validated external OAuth/OIDC (and future explicitly accepted federation protocol) subject maps server-side to a governed Principal/Identity.

Both paths produce the same provider-neutral governed actor context and both still require Administration operation-time authorization.

Provider roles, groups, scopes, tenant claims, or similar custom claims are not converted into Wyrmgate `AdministrativePermission`.

## External bearer authentication adapter

The first implemented runtime target remains an OAuth/OIDC-compatible JWT resource server configured with:

- one trusted issuer URI;
- one required Wyrmgate API audience;
- issuer-discovered signing keys and normal JWT validity checks.

A valid external token contributes only the exact external authentication subject `(issuer, subject)`. It grants no IAM authority by itself.

When `iam.auth.enabled=false`, that external bearer adapter is disabled. This no longer means Wyrmgate as a product has no possible authentication mechanism; first-party authentication is separately configured under the ADR-0042 IdP/SSO implementation boundary. Until that implementation slice is active, protected control-plane routes remain fail closed.

Health, system information, protocol discovery/JWKS where explicitly enabled, and API-documentation endpoints may remain readable as specifically configured.

## First-party authentication adapter

First-party authentication must resolve an existing governed Principal and its owning Identity/Tenant server-side. It may not create a parallel `User` or accept client-writable tenant/authority claims.

Local authenticator lifecycle is Credential-owned. Raw passwords, WebAuthn private material, recovery secrets, client secrets, signing private keys and equivalent private material never appear in ordinary IAM APIs, events, AuditRecord content, logs, URLs or browser storage.

A successful first-party login establishes an authenticated session only. Administrative authorization is still evaluated by Administration for every protected semantic operation.

## Provider-neutral authentication assurance

The trusted control-plane actor context carries a canonical `AuthenticationAssuranceContext` with semantic level `BASELINE` or `STRONG`, plus authentication/step-up timestamps where available. This is a provider-neutral input to Administration authorization and is distinct from bearer/token/session validation itself.

The current external bearer resolver maps a valid bearer-authenticated request to `BASELINE` unless a deployment adapter establishes stronger assurance. First-party authentication must likewise map concrete methods into canonical assurance through an explicit trusted adapter. Raw provider claims, OIDC `acr`/`amr` names, authenticator vendor fields, and provider-specific values do not become canonical IAM semantics directly.

Break-glass uses this assurance contract both at activation and at operation time. Missing, stale or downgraded strong assurance fails closed while leaving ordinary direct/delegated/elevated authorization semantics unchanged.

## Governed actor resolution

Administration owns the control-plane binding/actor-resolution boundary.

For external federation:

```text
validated issuer + subject
        |
        v
ControlPlaneActorBinding
        |
        +--> TenantContext
        +--> governed Identity ID
```

For first-party Wyrmgate authentication:

```text
authenticated governed Principal
        |
        v
server-side Principal → Identity/Tenant resolution
        |
        +--> TenantContext
        +--> governed Identity ID
```

Neither path grants IAM permission by itself. The resolved actor still passes normal Administration operation-time checks, including current governed Identity eligibility, semantic permission, resource, scope, temporal grant validity, assurance and tenant isolation.

The caller never selects its tenant or governed Identity through writable headers/token claims.

## Initial administrator bootstrap

A fresh tenant intentionally has no administrative grant and therefore cannot authorize a normal administrative API call. Its first grant is established through an explicit operator-only one-shot server command/process, not a permanent bootstrap account.

The selected administrator must already exist as an `ACTIVE` governed Identity in the target tenant.

ADR-0042 permits the bootstrap authentication anchor to be either:

- an external issuer+subject binding, or
- an existing first-party governed Principal that can authenticate through an active Credential.

The bootstrap transaction still creates durable burn-once evidence plus one explicit initial administrative role/grant. It creates no wildcard permission, universal OAuth scope, blanket application access or hidden superuser semantic.

The initial role permission membership remains explicit and governed by the current accepted Administration/bootstrap contract. `GLOBAL` broadens only the resource scope of listed permissions; it is not a wildcard permission.

## Burn-once invariant

Bootstrap is permanently one-time per tenant.

The tenant-unique bootstrap marker remains durable even if the original grant is later revoked. The system must never infer bootstrap eligibility from the current absence of an active administrator. Losing all administrative authority after bootstrap therefore requires a governed recovery mechanism. Bootstrap must never be repurposed as that recovery mechanism.

The marker is inserted inside the same authoritative Administration transaction as the role/grant/binding state required by that bootstrap mode. If any part fails, the transaction rolls back and the marker is not burned.

## External bearer runtime configuration

The existing external bearer adapter uses:

```text
IAM_AUTH_ENABLED=true
IAM_AUTH_ISSUER_URI=https://issuer.example
IAM_AUTH_AUDIENCE=wyrmgate-api
```

Those settings configure federation/resource-server behavior; they no longer define Wyrmgate's full product authentication model.

First-party IdP/SSO configuration is defined separately and must fail closed unless issuer/base URL, signing material, session security and required persistence/security dependencies are valid.

## Browser/session rules

The management console should authenticate through a same-origin secure session when first-party SSO is enabled. Long-lived bearer tokens or refresh tokens must not be stored in browser `localStorage` or `sessionStorage`.

Cookie-authenticated state-changing browser endpoints require CSRF protection. Session cookies require deployment-appropriate `Secure`, `HttpOnly` and `SameSite` controls, bounded idle/absolute expiry, fixation protection and logout invalidation.

## Sensitive-data rules

Wyrmgate does not persist or expose raw bearer tokens, authorization codes, refresh tokens, signing private keys, raw client secrets, passwords or private authenticator material as ordinary IAM state/evidence.

External binding may persist exact issuer and external subject where required as its authoritative mapping key. First-party authentication uses governed Principal/Identity references rather than provider-like synthetic claims.

Authentication failures return stable semantic errors and correlation IDs rather than JWT/framework/SQL/secret details.

## Current implementation boundary

The repository currently implements the external JWT resource-server adapter and governed actor-resolution path. ADR-0042 makes first-party Wyrmgate IdP/SSO the next authentication capability tranche rather than an optional unrelated product.

Until first-party authentication/session/token issuance is implemented and verified, deployments can continue to use the existing external bearer adapter. No code path may weaken operation-time Administration authorization during the transition.
