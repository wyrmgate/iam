# Identity Provider and Single Sign-On Security Boundary

## Purpose

This document defines the implementation-facing first-party authentication, federation and SSO boundary introduced by ADR-0042. Wyrmgate is an enterprise IAM platform with integrated IdP/SSO and IGA capabilities.

Authentication, SSO protocol issuance, business access governance and IAM administrative authorization remain separate decisions.

## Canonical ownership

- Identity owns the governed subject and Principal lifecycle.
- Credential owns authenticator/credential metadata and lifecycle for a Principal.
- Catalog owns the governed Application and typed SSO-facing registration inputs.
- Access/Governance own business access intent and policy decisions.
- Administration owns permission to configure and operate IAM/IdP administration.
- Audit owns immutable data-minimized security evidence.
- Platform provides protocol/session/token/signing infrastructure behind semantic ports.

Protocol framework entities must not become a competing canonical `User`, `Role`, `Permission`, `Application` or `Credential` model.

## First-party authentication

A standalone deployment may authenticate a governed Principal using first-party authenticators. The initial password/WebAuthn/etc. concrete choices are implementation slices, but all implementations must preserve these invariants:

- authentication is performed against an existing governed Principal;
- the owning Identity and Principal must be semantically eligible for login;
- authenticator lifecycle is Credential-owned;
- raw password, private key, recovery secret or equivalent private material never appears in ordinary Credential APIs, browser storage, logs, audit payloads, task payloads or errors;
- secret/verifier material is resolved only through an explicit security adapter;
- compromise/revocation takes effect at authentication/session policy boundaries without rewriting historical governance decisions.

Checkpoint 3 now implements those boundaries as semantic ports rather than protocol-framework user state:

- Identity exposes an authentication-only query that returns a minimal subject projection only when the Principal is active, correlated to an existing Identity, and that Identity is active;
- Credential selects only tenant-bound, active, temporally effective `PASSWORD` credentials for the Principal;
- private verification occurs through a `CredentialSecretVerifier` adapter keyed by the opaque Credential `SecretReference`; raw verifier material remains outside Credential persistence;
- missing or ambiguous verifier adapters fail closed;
- the verifier result is explicitly bound to the Principal plus Credential identity/revision needed to establish and subsequently revalidate the browser session; a credential proof for one Principal cannot establish a session for another.

No production secret-provider implementation is implied by this boundary. A deployment must provide an approved adapter for its secret/verifier store before local password authentication can succeed.

There is no permanent built-in `admin/admin`, hidden root credential or reusable bootstrap password.

## Federated authentication

Wyrmgate may trust explicitly configured external authentication providers. Federation adapters validate the upstream protocol and map the resulting provider subject to a governed Principal/Identity through server-side configuration.

Provider groups, roles, scopes, tenant claims and provider-native assurance names do not become Wyrmgate AdministrativePermission or business access automatically. Any promotion into governed canonical state requires an explicit typed mapping/policy owned by the relevant capability.

## OIDC/OAuth protocol baseline

The first accepted IdP/SSO protocol baseline is OpenID Connect over OAuth 2.x with Authorization Code + PKCE.

Required protocol surfaces:

- `/.well-known/openid-configuration`;
- public JWKS endpoint;
- authorization endpoint;
- token endpoint;
- signed ID tokens and access tokens;
- exact redirect URI matching;
- state, nonce and PKCE enforcement where applicable;
- bounded browser SSO sessions;
- logout/session termination;
- governed client/application registration;
- signing-key rotation with retained public verification material for bounded overlap;
- explicit curated claim release.

Additional OAuth/OIDC features such as refresh tokens, revocation, introspection, device authorization, client credentials, PAR/JAR/JARM, dynamic client registration and token exchange require explicit contracts before activation.

## Subject semantics

OIDC `sub` is a stable public protocol identifier. It is not automatically the database primary key of Identity or Principal.

The subject policy must be stable, tenant-safe and non-secret. Pairwise/public subject modes may be added only through an explicit accepted contract.

## Claim release

Claims are data-minimized projections for a particular relying party/audience. A token must never be treated as a serialized Identity360 graph.

The baseline may expose only explicitly configured standard claims and typed application-specific claims. It must not dump:

- AdministrativeRole/Grant/Delegation/Elevation/BreakGlass state;
- raw AccessAssignment records;
- unrestricted canonical attributes;
- provider observations;
- credential material or secret references;
- internal correlation or persistence details not intended as public protocol contract.

OAuth scopes define protocol consent/capability for the relying party. They do not create Wyrmgate Administration permissions.

## Application access decision

Authentication success does not necessarily mean the Identity may use every registered application.

Where an application requires governed access, the SSO authorization path queries current Access/Catalog semantics. The protocol layer consumes a semantic allow/deny result and must not mutate Access state or infer entitlement from token claims.

Privilege increases fail closed when mandatory governance/access evaluation required for token issuance is unavailable. Existing authoritative reductions/revocations must not be ignored merely because an unrelated evaluator is unavailable.

## Browser and session security

The management console and first-party login UI must use secure same-origin browser/session handling. Long-lived bearer tokens, refresh tokens, client secrets and authenticator material must not be placed in `localStorage` or exposed in URLs.

Session cookies must use deployment-appropriate Secure, HttpOnly and SameSite protections. CSRF protection is required for cookie-authenticated state-changing browser endpoints. Session fixation protection, bounded idle/absolute lifetime and logout invalidation are mandatory.

The checkpoint-3 session runtime uses a fresh 256-bit opaque token for every successful establishment. Only a SHA-256 digest is persisted. Session rows are tenant-bound and contain the Principal/Identity plus the Credential ID and revision that established authentication; they carry no Administration permission, AccessAssignment, application entitlement or OAuth scope. Session establishment requires the Credential-owned verification proof to name the same Principal as the Identity-owned authentication subject. Default bounds are 30 minutes idle and 8 hours absolute. Every session resolution revalidates current Principal/Identity login eligibility and the establishing Credential's ownership, kind, lifecycle, temporal validity and revision. Revocation, compromise or another Credential lifecycle revision therefore invalidates the session fail closed. Logout marks the session revoked; raw session tokens are never persisted.

The public login/logout HTTP contract and cookie attributes are intentionally not activated by this internal checkpoint until tenant routing/authentication-interaction semantics are fixed. Consequently this slice does not weaken the existing public security chain and does not claim CSRF completion before a cookie-authenticated mutation surface exists.

## Control-plane authentication

ADR-0011 external bearer authentication remains supported. ADR-0042 adds first-party Wyrmgate authentication as another source of the same provider-neutral governed actor context.

Regardless of authentication source:

1. identify the authenticated Principal/provider subject;
2. resolve the governed Identity and Tenant server-side;
3. establish provider-neutral authentication assurance;
4. perform Administration operation-time authorization.

The browser cannot supply authoritative tenant or administrative permission claims.

## Initial administrator bootstrap

Burn-once bootstrap remains operator-controlled and default-deny. It may target either an externally authenticated subject or an existing first-party Principal with an active login authenticator.

Bootstrap only establishes the explicit Administration role/grant defined by the accepted bootstrap contract. It does not create wildcard SSO scopes or blanket application access.

## Signing and key management

Token-signing private keys remain behind Platform signing infrastructure. Public JWK material is intentionally publishable. Private keys are never returned through administrative or protocol APIs.

Key rotation must support an overlap window where previously issued tokens can still be validated until their bounded expiry while newly issued tokens use the current active signing key.

## Audit and secret boundaries

Audit may record data-minimized semantic events such as:

- authentication success/failure;
- session establishment/termination;
- client registration/configuration changes;
- federation mapping changes;
- authenticator revocation/compromise;
- signing-key administrative lifecycle events.

Audit/logging must never include passwords, authorization codes, bearer/access tokens, refresh tokens, raw client secrets, private signing keys, recovery secrets or raw authenticator material.

## Implementation checkpoints

The implementation should land in bounded slices:

1. protocol/security foundation and OIDC metadata/JWKS;
2. governed SSO client registration;
3. first-party local authentication and secure browser session;
4. Authorization Code + PKCE and token issuance;
5. console sign-in through first-party SSO;
6. federation adapters;
7. optional protocol extensions only when explicitly accepted.

Checkpoint 3 is being delivered in two deliberately separated layers: the authentication/session semantic runtime first, followed by the public browser interaction once tenant-routing and protocol interaction semantics are explicit. This does not move Authorization Code/token issuance from checkpoint 4.

Each slice requires contract tests, tenant-isolation tests, secret-boundary tests and negative security cases before merge.
