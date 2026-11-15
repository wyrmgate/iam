# Identity Provider and Single Sign-On Security Boundary

## Purpose

This document defines the implementation-facing first-party authentication, federation, SSO, and control-plane browser-session boundary accepted by ADR-0042. Authentication, SSO protocol issuance, business access governance, and IAM administrative authorization remain distinct decisions.

## Canonical ownership

Identity owns governed subjects and Principal lifecycle; Credential owns authenticator metadata/lifecycle; Catalog owns Applications and SSO relying-party registration; Access/Governance own business access intent and policy decisions; Administration owns IAM administrative authority; Audit owns immutable data-minimized evidence; Platform supplies protocol/session/token/signing mechanics. Protocol or security framework objects are never competing canonical IAM authority.

## First-party authentication

Wyrmgate authenticates an existing governed Principal through Credential-owned PASSWORD authenticators. Tenant is first derived server-side from a globally unique active Catalog SSO `client_id`. The native Principal key is a tenant-scoped selector; ADR-0069 resolves the authentication ApplicationTarget from Catalog-owned SSO registration, never public login input; Identity requires the selected Principal and owning Identity to be ACTIVE. Credential independently verifies an effective authenticator for that Principal behind the secret-verifier boundary. ADR-0064 bounds password candidate lookup to eight effective candidates plus an overflow sentinel; overflow fails closed. Verification proof binds the trusted tenant, Principal, exact Credential ID and revision. Session establishment rechecks the current eligible subject and Credential before minting a session.

All authentication failures are intentionally non-enumerating. Passwords and private verifier material never enter ordinary persistence, logs, audit, events, errors, URLs, or browser storage. There is no permanent built-in administrator password, hidden root credential, or wildcard superuser.

## Browser-session security

Successful login always establishes a fresh 256-bit opaque session token, preventing fixation. Only SHA-256 digests are persisted. A global unique digest index permits the server to locate the session and derive Tenant from server-side state for same-origin control-plane requests; browser input never selects authoritative Tenant.

Sessions default to 30 minutes idle and 8 hours absolute and contain only authentication context: session ID, Principal/Identity IDs, exact establishing Credential ID/revision, and time bounds. They carry no Administration permission, AccessAssignment, entitlement, or OAuth scope.

Every resolution revalidates current Principal/Identity login eligibility and exact Credential ownership, kind, lifecycle, temporal validity, and revision. Any invalidation fails closed and revokes the session opportunistically. Logout revokes server state before clearing browser cookies.

## Cookie boundary

The authentication cookie is `__Host-wyrmgate_sso` and is emitted with `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, and no Domain attribute. Browser JavaScript cannot read the authentication token.

Cookie-authenticated state-changing control-plane requests and logout require double-submit CSRF proof. `__Host-wyrmgate_csrf` is a high-entropy Secure, SameSite=Strict, Path=/, no-Domain cookie intentionally readable by same-origin JavaScript; the same value must be supplied in `X-Wyrmgate-CSRF`. CSRF proof is not authentication authority.

The management console uses same-origin `/api/*` calls with browser credentials and does not place long-lived bearer/access tokens, refresh tokens, passwords, authorization codes, session tokens, client secrets, or private key material in localStorage/sessionStorage or URLs.

## Control-plane authority

A valid first-party session creates only provider-neutral authenticated actor context from the server-derived Tenant and governed Identity. Administration authorization remains final at operation time for every protected control-plane operation.

The external JWT/OIDC resource-server adapter remains a supported federation-compatible control-plane authentication source. When external bearer mode is also enabled, an explicit bearer header takes precedence and follows validated issuer/audience plus server-side external-subject binding. External groups, roles, scopes, claims, tenant claims, or provider assurance strings never automatically become `AdministrativePermission`, business access, or canonical assurance.

## OIDC/OAuth protocol boundary

The first-party public baseline is OpenID Connect over OAuth Authorization Code with mandatory S256 PKCE. Implemented surfaces are discovery, JWKS, authorization, and token endpoints. Redirect URIs are exact registered strings. Unknown/retired clients and invalid redirects never cause redirects to untrusted input. Authorization codes are high-entropy, digest-only, five-minute, single-use technical credentials. State is opaque correlation data; nonce is optional protocol data, not authority.

If an SSO registration requires governed access, application access is evaluated through Catalog/Access semantic queries and fails closed when the mandatory privilege-increase evaluator is unavailable. The protocol layer never mutates Access state.

## Claims and token signing

Public `sub` is an opaque deterministic digest over the versioned subject-policy prefix, server-derived Tenant ID, and governed Identity ID. Raw internal IDs are not public token claims. ID/access claims remain deliberately minimal and never dump Administration grants, AccessAssignment state, unrestricted Identity attributes, provider observations, or Credential/secret data. OAuth scopes do not create Wyrmgate administrative permissions.

Private signing material remains behind Platform `SigningKeyProvider`; only public JWK material is exposed. JWKS publishes the current signing verification key plus explicitly retained public verification keys. The current key must be present in the verification set, duplicate key IDs fail closed, and retained keys can overlap during bounded rotation so still-valid older tokens remain verifiable. Private signing material is never published.

## Federated authentication and explicit adapter boundary (ADR-0071)

Integration owns explicit tenant-bound provider trust (stable ID/revision, exact protocol, issuer/audience, provider key) and active exact stable-subject links to existing Principals. It must validate same-Tenant references through Identity and enforce unique active links and explicit revisioned retirement/remap. Neither PrincipalObservationMapping nor Administration external actor binding is a federation link. No provider claim selects Tenant or creates Identity/Principal/Access/Administration authority.

The typed adapter accepts trusted VerificationContext and provider-specific request. Cryptographic signature/keys, issuer/audience, nonce/InResponseTo, callback binding, time and durable one-use replay checks are mandatory before returning VerifiedFederatedSubject. Authentication resolves exact trust/link and ACTIVE Tenant/eligible subject through semantic reads. The typed federated anchor pins trust/link/Tenant/Identity/Principal IDs and revisions and proof validity; no raw assertion or synthetic Credential. Revalidation fails closed on changed or missing state. No public federated session endpoint is wired; concrete trust/link persistence, callback/replay and session activation follow in Integration. Current first-party sessions remain local-only. The existing external JWT control-plane path remains independent and unchanged.

## Optional protocol extensions

Protocol-library features do not become product contracts automatically. The following remain disabled/deferred until separately accepted, typed, secured, documented, and tested: refresh tokens, token introspection/revocation, RP-initiated logout, device authorization, confidential clients/client-secret authentication, client credentials, PAR, JAR, JARM, dynamic client registration, token exchange, and broad profile/email claim release.

Enabling any extension requires an explicit public contract, capability ownership analysis, secret boundary, tenant routing, authorization semantics, lifecycle/rotation rules, and negative security coverage. No generic extension registry may bypass these requirements.

## Audit and observability

Audit may record data-minimized semantic evidence for authentication, session lifecycle, client configuration, signing-key administration, federation mapping, and suspicious authenticator events. Passwords, authorization codes, access/bearer tokens, raw session tokens, refresh tokens, client secrets, private keys, recovery secrets, SAML assertions, upstream authorization codes, and raw authenticator material are prohibited from ordinary audit/log payloads and error diagnostics.

## Implementation checkpoints

1. protocol/security foundation and JWKS — implemented;
2. governed SSO client registration — implemented;
3. first-party local authentication and bounded browser-session runtime — implemented;
4. Authorization Code + PKCE, exact redirect validation, state/nonce handling, discovery, and signed token issuance — implemented;
5. same-origin console sign-in/session/logout, secure cookies, fixation prevention, CSRF protection, and first-party control-plane actor context — implemented;
6. explicit federation adapter/extension boundary, external JWT/OIDC compatibility, and retained JWKS verification-key overlap — implemented.

Concrete upstream OIDC/SAML login providers and optional OAuth/OIDC extensions are future product slices, not incomplete baseline behavior. Each future activation requires its own explicit accepted contract and negative security coverage.

### Local password change

ADR-0064 permits only a current first-party browser session's exact local PASSWORD Credential to be changed through the dedicated same-origin `/api/auth/password/change` interaction (CSRF, current password reproof, new password 15–1024 characters). Local verifier replacement, old authoritative revocation and completed rotation are atomic; old Credential revision sessions fail closed. No password-derived request fingerprint or plaintext persistence, generic enrollment, reset, external provider call or ordinary Credential secret API is introduced. Existing administrative `rotate` remains a metadata plan.

### Authorization-code redemption freshness (ADR-0066)

Revalidate the original tenant-scoped session, exact subject and effective Credential revision and current governed application access (when required) after code consumption and client/redirect/PKCE binding checks, before signing. Missing/denied context fails closed; unavailable mandatory evaluation produces no token. Exchange is not browser activity. Session touches are monotonic and reject expired/revoked rows. Previously issued stateless JWTs are not retroactively revoked.

### Establishing subject revisions (ADR-0067)

First-party sessions bind the active Tenant and exact Principal, Identity and Credential revisions. Current eligibility and exact revisions are checked on every resolution and code redemption. Any mismatch permanently invalidates the session, including suspend/reactivate cycles. Profile-only revision changes may force reauthentication until a narrower security epoch is defined. V31 revokes pre-upgrade sessions without inventing establishing revisions; trusted installation verification remains separate and may operate during PROVISIONING.

ADR-0069 adds a short-lived browser-bound single-use interaction with typed original protocol request, pinned client revision/tenant/target and independent form/cookie digests. It removes browser-selected client/target from Console login. Interactive login, prompt handling and exact redirect validation use the same subject/Credential/access checks as session reuse.

### Bounded authentication runtime (ADR-0070)

Shared database-time admission counters, bounded governed Access evaluation, mandatory success Audit evidence, best-effort denial/revocation evidence, and bounded technical retention are implemented. See [protocol contracts](../api/idp-sso-contracts.md#runtime-admission-evidence-and-retention-adr-0070--v33). Ingress rate limiting and live load/retention/Audit availability acceptance remain operational requirements. Discovery advertises only implemented openid claim scope; profile/email registration compatibility is not claim release.

ADR-0078/V38: existing browser session supports a mutually exclusive federated trust/link anchor, no synthetic Credential. Trust/link and Tenant/Identity/Principal revisions and proof expiry are revalidated on all session/code reads. Local password change remains local-only. Upstream callback activation is still separate.
