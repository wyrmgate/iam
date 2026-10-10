# First-Party IdP / SSO Protocol Contracts

## Purpose

This document is the living implementation contract for ADR-0042. Wyrmgate is an IAM platform with first-party Identity Provider (IdP), Single Sign-On (SSO), and IGA capabilities. IdP/SSO is a composed product service over the canonical capabilities; it does not create another authoritative User, Role, Permission, Application, Principal, Credential, or access model.

## Current implementation checkpoint

The first-party IdP is disabled by default. When `iam.idp.enabled=true`, checkpoint 5 provides:

- OIDC discovery and public JWKS;
- Authorization Code with mandatory S256 PKCE for Catalog-managed public clients;
- exact registered redirect validation, bounded state/nonce handling, one-time authorization codes, and signed short-lived ID/access tokens;
- Credential-owned local password verification and bounded first-party browser sessions;
- same-origin `/api/auth/login`, `/api/auth/session`, and `/api/auth/logout` interaction for the Wyrmgate console;
- secure HttpOnly first-party session cookies, session fixation prevention, cookie-authenticated CSRF enforcement, and logout invalidation;
- first-party browser-session authentication for `/api/v1/**` while preserving operation-time Administration authorization;
- compatibility with the existing external bearer/JWT control-plane mode when it is also enabled.

Federation adapters remain checkpoint 6 and require explicit contracts before activation.

## Canonical ownership

- Identity owns governed subjects and Principal lifecycle.
- Credential owns authenticators and secret-reference lifecycle for Principals.
- Catalog owns Applications and SSO relying-party registration.
- Access/Governance own business access intent and governance decisions.
- Administration owns IAM administrative authority.
- Audit owns immutable data-minimized security evidence.
- Platform owns short-lived protocol/session/signing mechanics behind semantic ports.

Authentication success, SSO token issuance, application access, and Administration authorization are separate decisions.

## OIDC/OAuth baseline

Implemented public surfaces when `iam.idp.enabled=true`:

- `GET /.well-known/openid-configuration`;
- `GET /oauth2/jwks`;
- `GET /oauth2/authorize`;
- `POST /oauth2/token`.

The baseline is Authorization Code with `code_challenge_method=S256`. Redirect URIs are exact registered strings; wildcard/prefix matching is prohibited. `state` is an opaque bounded client correlation value. Optional `nonce` is carried into the ID token. Authorization codes are 256-bit random values, single-use, five minutes by default, and only SHA-256 digests are persisted. Refresh tokens are not issued.

Unknown or retired clients and invalid redirect URIs produce direct errors; the server never redirects to an unvalidated URI. After redirect validation, protocol errors may be returned to that exact URI with valid caller state.

## Server-derived Tenant routing

Unauthenticated protocol and login routing starts from a server-generated globally unique Catalog `client_id`. `SsoClientProtocolQuery` resolves that identifier to an active registration and authoritative `TenantContext`. Browser-supplied Tenant headers, claims, query parameters, state, nonce, and target identifiers never become authoritative Tenant input.

For local interactive login, the browser supplies an `applicationTargetId` and native Principal key only as tenant-scoped lookup selectors. Identity performs the lookup inside the server-derived Tenant and requires an ACTIVE Principal correlated to an ACTIVE Identity. Credential then verifies an effective PASSWORD authenticator for that exact Principal.

## Governed client registration

`SsoClientRegistration` remains Catalog-owned authoritative configuration. The public-client baseline uses server-generated globally unique client IDs, no client secret, mandatory S256 PKCE, exact redirect URI registration, `openid` plus optional `profile`/`email` scope, and `ACTIVE -> RETIRED` lifecycle. Protocol resolution requires both the registration and parent Application to remain active.

Management endpoints remain under `/api/v1/applications/{applicationId}/sso-clients` and `/api/v1/sso-clients/**` with tenant binding and revision semantics. Runtime OpenAPI is available under `/api/openapi`.

## Same-origin first-party browser interaction

When first-party IdP is enabled, the console may use:

### `POST /api/auth/login`

JSON input:

- `clientId`: globally unique Catalog SSO client identifier used only for server-side Tenant routing;
- `applicationTargetId`: tenant-scoped Identity Principal lookup target;
- `principalKey`: native Principal key;
- `password`: presented secret, never persisted or logged.

The response is deliberately non-enumerating: invalid client routing, target, Principal/Identity eligibility, credential state, and password proof all fail as `invalid_credentials`. A successful authentication always establishes a fresh opaque browser session, preventing session fixation.

### `GET /api/auth/session`

Returns whether the current first-party browser session is authenticated. A valid session refreshes the bounded idle expiry after revalidating current Identity/Principal and establishing Credential state. The response also rotates the non-HttpOnly CSRF double-submit cookie used by same-origin mutation requests.

### `POST /api/auth/logout`

Requires CSRF proof when a first-party session cookie is present, revokes the server-side session, and clears the browser session and CSRF cookies.

## Cookie and CSRF contract

The authentication cookie is `__Host-wyrmgate_sso` with `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, and no Domain attribute. The raw 256-bit opaque value is never persisted; only its SHA-256 digest is stored.

The CSRF cookie is `__Host-wyrmgate_csrf` with `Secure`, `SameSite=Strict`, `Path=/`, no Domain attribute, and is intentionally readable by same-origin JavaScript. Cookie-authenticated state-changing `/api/v1/**` requests and logout must echo it in `X-Wyrmgate-CSRF`. The value is CSRF proof, not an authentication credential.

Browser code must not store passwords, session tokens, authorization codes, bearer/access tokens, refresh tokens, client secrets, or private key material in localStorage/sessionStorage or URLs.

## First-party control-plane authentication

The browser-session digest is globally unique in Platform persistence. Possession of a valid opaque cookie lets the server locate the session and derive Tenant from server-side state; the browser does not submit authoritative Tenant. The session is then revalidated against current Identity/Principal eligibility and exact Credential ID/revision before it may establish provider-neutral control-plane actor context.

The session contains no `AdministrativePermission`, AccessAssignment, application entitlement, or OAuth-scope authority. `/api/v1/**` operations still perform normal Administration authorization at operation time.

If external bearer authentication (`iam.auth.enabled=true`) is also enabled, an explicit bearer header takes precedence and follows the existing validated issuer/audience plus external-subject binding flow. External provider groups, roles, scopes, or claims do not automatically become Wyrmgate administrative authority.

## Session bounds and invalidation

Browser sessions default to 30 minutes idle and 8 hours absolute. Resolution fails closed and opportunistically revokes the session when:

- the session is expired or revoked;
- Principal or Identity is no longer login-eligible;
- the establishing Credential no longer belongs to the Principal;
- Credential kind/lifecycle/temporal validity changes;
- the exact establishing Credential revision changes.

Logout is server-side invalidation, not merely cookie deletion.

## Subjects, claims, and signing

Public token subject is the opaque deterministic policy:

`base64url(SHA-256("wyrmgate-sub-v1|" + tenantId + "|" + identityId))`

ID tokens contain only issuer, subject, audience, issue/expiry times, authentication time, and optional nonce. Access tokens contain only issuer, subject, audience, issue/expiry times, client ID, and granted scope string. `profile`/`email` scope names do not authorize unrestricted Identity attribute release.

Tokens never automatically expose Administration role/grant/delegation/elevation/break-glass state, raw AccessAssignment collections, unrestricted canonical attributes, provider observations, Credential/secret references, or raw internal Tenant/Identity/Principal IDs.

Signing private keys remain behind Platform `SigningKeyProvider`. JWKS publishes public verification material only. Overlapping publication of retained verification keys remains a bounded follow-up and must be completed before claiming seamless signing-key rotation.

## Deferred features

Checkpoint 6 covers explicitly configured federation compatibility. Refresh tokens, introspection/revocation, device authorization, confidential clients, client credentials, PAR/JAR/JARM, dynamic registration, token exchange, and typed profile/email claim release remain deferred until explicitly accepted and contracted.

## Verification requirements

Security coverage must include tenant derivation/isolation, inactive subject denial, authenticator revocation/revision invalidation, session fixation prevention, exact redirect validation, PKCE downgrade/replay, CSRF rejection, logout invalidation, governed-access fail-closed behavior, claim minimization, signing/JWKS consistency, and secret non-disclosure.
