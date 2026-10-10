# Control-plane Authentication and Initial Administrator Bootstrap

## Purpose

This document defines the authentication boundary that precedes Wyrmgate Administrative Authorization. ADR-0011 established the external bearer implementation; ADR-0042 broadened Wyrmgate into an IAM/IdP/SSO platform and superseded the implication that an external IdP is the only canonical authentication source.

Authentication answers **which governed subject authenticated and through which trusted mechanism**. Administration answers **what that governed actor may do to IAM state**. These remain separate decisions.

See [`identity-provider-sso.md`](identity-provider-sso.md) for first-party IdP/SSO and federation security details.

## Supported authentication sources

Wyrmgate supports two canonical authentication-source families for the control plane:

1. **First-party Wyrmgate authentication** — an existing governed Principal authenticates using Credential-owned authenticator semantics and a server-side browser session.
2. **Federated/external authentication** — a cryptographically validated external OAuth/OIDC subject, and future explicitly accepted upstream provider adapters, map server-side to governed Wyrmgate actor semantics.

Both paths produce provider-neutral actor context and both still require Administration authorization for every protected operation. Provider roles, groups, scopes, tenant claims, arbitrary claims, or library authorities are never converted automatically into `AdministrativePermission`.

## External bearer adapter

The implemented external JWT resource-server adapter is configured with one trusted issuer URI and one required Wyrmgate API audience. Normal JWT issuer/signature/time/audience validation occurs before exact `(issuer, subject)` is passed to `ControlPlaneActorResolver`. Administration-owned binding resolves that external subject to a Tenant and governed Identity.

A valid token grants no IAM authority by itself. Browser-supplied Tenant headers and token/provider claims do not select authoritative Tenant.

Configuration:

```text
IAM_AUTH_ENABLED=true
IAM_AUTH_ISSUER_URI=https://issuer.example
IAM_AUTH_AUDIENCE=wyrmgate-api
```

When first-party IdP and external bearer modes are both enabled, an explicit bearer header takes precedence for that request; otherwise the same-origin first-party session may authenticate the request.

## First-party adapter

When `iam.idp.enabled=true`, local login routes Tenant from a globally unique active Catalog SSO client ID, resolves an ACTIVE Principal/Identity within that Tenant, verifies Credential-owned password proof, and establishes a fresh opaque session. Possession of the resulting globally unique session-token digest lets the server recover Tenant from server-side state; client input never supplies authoritative Tenant.

Every cookie-authenticated `/api/v1/**` request revalidates current Principal/Identity and exact establishing Credential state/revision before creating provider-neutral actor context. The session contains no Administration authority. State-changing requests require CSRF proof. Logout revokes the server-side session.

## Federation extension boundary

The existing external JWT/OIDC adapter is the concrete federation-compatible control-plane source today. ADR-0042 checkpoint 6 also defines a typed `FederatedAuthenticationAdapter<R>` SPI for future upstream OIDC/SAML authentication into first-party flows.

Adapters are explicitly wired by stable provider key and concrete request type. They must cryptographically validate provider-specific exchanges before returning only exact external `(issuer, subject)` plus authentication time. `FederationAdapterRegistry` enables nothing by default and rejects duplicate provider keys. Provider network calls, metadata, signing keys, OIDC token exchange, SAML assertions, and native claims stay behind provider-specific Integration adapters.

Upstream groups, roles, scopes, tenant claims, arbitrary attributes, and provider assurance strings are intentionally absent from the normalized verified-subject result. They do not become business access, `AdministrativePermission`, or canonical assurance automatically. Any future provider activation must separately define server-side Principal/Identity/Tenant mapping and security tests.

## Provider-neutral assurance

The trusted control-plane actor context carries canonical `AuthenticationAssuranceContext` (`BASELINE` or `STRONG`) plus trusted timestamps where available. Provider-specific `acr`/`amr`, authenticator vendor fields, or raw upstream labels do not directly become canonical assurance. A trusted adapter must explicitly map them when such a mapping is accepted. Break-glass continues to fail closed when required strong assurance is unavailable, stale, or downgraded.

## Governed actor resolution

External bearer flow:

```text
validated issuer + subject
        |
        v
ControlPlaneActorBinding
        |
        +--> TenantContext
        +--> governed Identity ID
```

First-party flow:

```text
server-resolved opaque browser session
        |
        +--> TenantContext
        +--> governed Principal / Identity
        |
        v
provider-neutral authenticated actor
```

Neither path grants IAM permission by itself. The actor still passes current Administration checks for governed Identity eligibility, semantic permission, resource/scope, temporal validity, assurance, and Tenant isolation.

## Initial administrator bootstrap

A fresh Tenant has no administrative grant and cannot authorize a normal administrative API call. Its first grant is established through the explicit operator-only burn-once process from ADR-0011, not a permanent bootstrap account.

The selected administrator must already be an ACTIVE governed Identity in the target Tenant. ADR-0042 permits the authentication anchor to be an existing external issuer+subject binding or an existing first-party governed Principal able to authenticate through an active Credential.

Bootstrap creates only explicit Administration role/grant/binding state required by that mode. It creates no wildcard permission, universal OAuth scope, blanket application access, hidden superuser, or reusable password. The durable Tenant-unique bootstrap marker remains burned even if the initial grant is later revoked; loss of all administrators requires a governed recovery mechanism, never re-running bootstrap.

## Browser/session rules

The management console uses same-origin first-party sessions when enabled. Long-lived bearer/access tokens or refresh tokens are not stored in browser `localStorage` or `sessionStorage`. The authentication cookie is Secure/HttpOnly/host-only with bounded idle and absolute expiry; authentication rotates the session to prevent fixation; logout invalidates server state. Cookie-authenticated state changes require CSRF protection.

## Sensitive-data rules

Raw bearer tokens, authorization codes, refresh tokens, session tokens, SAML assertions, upstream authorization codes, signing private keys, raw client secrets, passwords, and private authenticator material are never ordinary IAM state, evidence, logs, events, URLs, or error payloads.

External binding may persist exact issuer and external subject as its authoritative mapping key. First-party authentication uses governed Principal/Identity references. Authentication failures return stable semantic errors/correlation IDs rather than framework, SQL, provider, or secret details.

## Current implementation boundary

The repository now implements both supported control-plane authentication families at their accepted baseline: first-party same-origin browser sessions and external JWT/OIDC bearer authentication. It also exposes an explicit typed federation adapter boundary for future upstream OIDC/SAML provider integrations, with no provider implicitly enabled.

Optional OAuth/OIDC features and concrete upstream first-party-session federation providers remain future product scope until separately accepted and contracted. No future extension may weaken operation-time Administration authorization or bypass server-derived Tenant/governed-actor resolution.
