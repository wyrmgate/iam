# Identity Provider and Single Sign-On Security Boundary

## Purpose

This document defines the implementation-facing first-party authentication, federation, SSO, and control-plane browser-session boundary accepted by ADR-0042. Authentication, SSO protocol issuance, business access governance, and IAM administrative authorization remain distinct decisions.

## Canonical ownership

Identity owns governed subjects and Principal lifecycle; Credential owns authenticator metadata/lifecycle; Catalog owns Applications and SSO relying-party registration; Access/Governance own business access intent and policy decisions; Administration owns IAM administrative authority; Audit owns immutable data-minimized evidence; Platform supplies protocol/session/token/signing mechanics. Protocol or security framework objects are never competing canonical IAM authority.

## First-party authentication

Checkpoint 5 authenticates an existing governed Principal through Credential-owned PASSWORD authenticators. Tenant is first derived server-side from a globally unique active Catalog SSO `client_id`. The submitted ApplicationTarget ID and native Principal key are only tenant-scoped selectors; Identity requires the selected Principal and owning Identity to be ACTIVE. Credential independently verifies an effective authenticator for that Principal behind the secret-verifier boundary.

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

When external bearer mode is also enabled, an explicit bearer header takes precedence and follows validated issuer/audience plus server-side external-subject binding. External groups, roles, scopes, claims, or assurance labels never automatically become `AdministrativePermission` or business access.

## OIDC/OAuth protocol boundary

The first-party public baseline is OpenID Connect over OAuth Authorization Code with mandatory S256 PKCE. Implemented surfaces are discovery, JWKS, authorization, and token endpoints. Redirect URIs are exact registered strings. Unknown/retired clients and invalid redirects never cause redirects to untrusted input. Authorization codes are high-entropy, digest-only, five-minute, single-use technical credentials. State is opaque correlation data; nonce is optional protocol data, not authority.

If an SSO registration requires governed access, application access is evaluated through Catalog/Access semantic queries and fails closed when the mandatory privilege-increase evaluator is unavailable. The protocol layer never mutates Access state.

## Claims and token signing

Public `sub` is an opaque deterministic digest over the versioned subject-policy prefix, server-derived Tenant ID, and governed Identity ID. Raw internal IDs are not public token claims. ID/access claims remain deliberately minimal and never dump Administration grants, AccessAssignment state, unrestricted Identity attributes, provider observations, or Credential/secret data. OAuth scopes do not create Wyrmgate administrative permissions.

Private signing material remains behind Platform `SigningKeyProvider`; only public JWK material is exposed. Current JWKS publishes the active signing key. Retained verification-key overlap must be published before seamless key rotation is claimed.

## Federated authentication

Federation remains checkpoint 6. Any adapter must explicitly validate the upstream protocol, derive Tenant server-side, and map the upstream provider subject to governed Principal/Identity state. Provider-native groups/roles/scopes/tenant claims remain observation/protocol input until explicitly governed; they never silently create Wyrmgate authority.

## Audit and observability

Audit may record data-minimized semantic evidence for authentication, session lifecycle, client configuration, signing-key administration, federation mapping, and suspicious authenticator events. Passwords, authorization codes, access/bearer tokens, raw session tokens, refresh tokens, client secrets, private keys, recovery secrets, and raw authenticator material are prohibited from ordinary audit/log payloads and error diagnostics.

## Implementation checkpoints

1. protocol/security foundation and JWKS — implemented;
2. governed SSO client registration — implemented;
3. first-party local authentication and bounded browser-session runtime — implemented;
4. Authorization Code + PKCE, exact redirect validation, state/nonce handling, discovery, and signed token issuance — implemented;
5. same-origin console sign-in/session/logout, secure cookies, fixation prevention, CSRF protection, and first-party control-plane actor context — implemented by this checkpoint;
6. federation adapters and explicitly accepted optional extensions — deferred.

Each slice requires negative security coverage for the surface it activates.
