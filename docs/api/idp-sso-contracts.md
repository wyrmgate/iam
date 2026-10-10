# First-Party IdP / SSO Protocol Contracts

## Purpose

This document is the living implementation contract for ADR-0042. Wyrmgate is an IAM platform with first-party Identity Provider (IdP), Single Sign-On (SSO), federation boundaries, and IGA capabilities. IdP/SSO is a composed product service over canonical capability owners; it does not create another authoritative User, Role, Permission, Application, Principal, Credential, or access model.

## Current implementation checkpoint

The bounded ADR-0042 runtime baseline is implemented. The first-party IdP remains disabled by default and is activated only by explicit configuration. When enabled it provides:

- OIDC discovery and public JWKS;
- Authorization Code with mandatory S256 PKCE for Catalog-managed public clients;
- exact registered redirect validation, bounded state/nonce handling, one-time authorization codes, and signed short-lived ID/access tokens;
- Credential-owned local password verification and bounded first-party browser sessions;
- same-origin `/api/auth/login`, `/api/auth/session`, and `/api/auth/logout` interaction for the Wyrmgate console;
- secure HttpOnly first-party session cookies, session fixation prevention, cookie-authenticated CSRF enforcement, and logout invalidation;
- first-party browser-session authentication for `/api/v1/**` while preserving operation-time Administration authorization;
- compatibility with the existing external bearer/JWT control-plane federation mode;
- an explicit typed OIDC/SAML upstream-federation adapter boundary with no provider implicitly enabled;
- JWKS publication of the current key plus explicitly retained public verification keys for bounded signing-key rotation overlap.

## Canonical ownership

- Identity owns governed subjects and Principal lifecycle.
- Credential owns authenticators and secret-reference lifecycle for Principals.
- Catalog owns Applications and SSO relying-party registration.
- Access/Governance own business access intent and governance decisions.
- Administration owns IAM administrative authority and external control-plane subject binding.
- Audit owns immutable data-minimized security evidence.
- Platform owns short-lived protocol/session/signing mechanics behind semantic ports.
- Integration/provider adapters own provider-specific federation transport and verification behavior.

Authentication success, SSO token issuance, application access, federation verification, and Administration authorization are separate decisions.

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

A future upstream federation provider must likewise map a cryptographically verified external subject to governed Principal/Identity/Tenant state server-side. Provider-native tenant claims are never authoritative routing input.

## Governed client registration

`SsoClientRegistration` remains Catalog-owned authoritative configuration. The implemented public-client baseline uses server-generated globally unique client IDs, no client secret, mandatory S256 PKCE, exact redirect URI registration, `openid` plus optional `profile`/`email` scope, and `ACTIVE -> RETIRED` lifecycle. Protocol resolution requires both the registration and parent Application to remain active.

Management endpoints remain under `/api/v1/applications/{applicationId}/sso-clients` and `/api/v1/sso-clients/**` with tenant binding and revision semantics. Runtime OpenAPI is available under `/api/openapi`.

Confidential-client authentication is not part of the implemented baseline even though ADR-0042 allows it where explicitly configured. It requires a future accepted typed secret/reference lifecycle and client-authentication contract.

## Same-origin first-party browser interaction

### `POST /api/auth/login`

JSON input contains `clientId`, tenant-scoped `applicationTargetId`, native `principalKey`, and presented `password`. Password material is never persisted/logged. The response is deliberately non-enumerating: invalid client routing, target, Principal/Identity eligibility, credential state, and password proof all fail as `invalid_credentials`. Successful authentication always establishes a fresh opaque browser session.

### `GET /api/auth/session`

Returns whether the current first-party browser session is authenticated. A valid session refreshes bounded idle expiry after revalidating current Identity/Principal and establishing Credential state. The response also rotates the non-HttpOnly CSRF double-submit cookie.

### `POST /api/auth/logout`

Requires CSRF proof when a first-party session cookie is present, revokes server-side session state, and clears browser session/CSRF cookies.

## Cookie and CSRF contract

`__Host-wyrmgate_sso` is `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, with no Domain attribute. The raw 256-bit opaque value is never persisted; only its SHA-256 digest is stored.

`__Host-wyrmgate_csrf` is `Secure`, `SameSite=Strict`, `Path=/`, no Domain, and intentionally readable by same-origin JavaScript. Cookie-authenticated state-changing `/api/v1/**` requests and logout must echo it in `X-Wyrmgate-CSRF`. The value is CSRF proof, not authentication authority.

Browser code must not store passwords, session tokens, authorization codes, bearer/access tokens, refresh tokens, client secrets, or private key material in localStorage/sessionStorage or URLs.

## First-party and external control-plane authentication

The browser-session digest is globally unique in Platform persistence. Possession of a valid opaque cookie lets the server locate the session and derive Tenant from server-side state. The session is then revalidated against current Identity/Principal eligibility and exact Credential ID/revision before establishing provider-neutral actor context.

The session contains no `AdministrativePermission`, AccessAssignment, application entitlement, or OAuth-scope authority. `/api/v1/**` operations still perform normal Administration authorization at operation time.

If external bearer authentication (`iam.auth.enabled=true`) is also enabled, an explicit bearer header takes precedence and follows validated issuer/audience plus external-subject binding. External provider groups, roles, scopes, tenant claims, or arbitrary claims do not automatically become Wyrmgate administrative authority.

## Federation adapter contract

`FederatedAuthenticationAdapter<R>` is the explicit upstream federation SPI. Each implementation declares:

- a stable provider key;
- `OIDC` or `SAML` protocol family;
- a provider-specific concrete request type;
- a cryptographic verification operation that returns only `VerifiedFederatedSubject`.

`VerifiedFederatedSubject` contains exact external `(issuer, subject)` and authentication time. It does not expose provider groups/roles/scopes/tenant claims/native attribute bags to core authority code. The provider-specific typed request and all network/metadata/key/assertion/token processing remain behind the adapter.

`FederationAdapterRegistry` contains only explicitly wired adapters, rejects duplicate or invalid provider keys, and exposes descriptors rather than an untyped invocation API. An empty registry enables no upstream provider. Merely adding an OAuth/SAML library does not activate federation.

A concrete provider endpoint and mapping flow must be accepted separately before use; it must map verified external subject to governed Principal/Identity/Tenant semantics server-side and must not convert provider claims directly into Access or Administration authority.

## Session bounds and invalidation

Browser sessions default to 30 minutes idle and 8 hours absolute. Resolution fails closed and opportunistically revokes the session when it is expired/revoked, Principal or Identity is no longer eligible, the establishing Credential no longer belongs to the Principal, Credential lifecycle/temporal validity changes, or the exact Credential revision changes. Logout is server-side invalidation, not merely cookie deletion.

## Subjects, claims, signing, and rotation

Public token subject is:

`base64url(SHA-256("wyrmgate-sub-v1|" + tenantId + "|" + identityId))`

ID tokens contain only issuer, subject, audience, issue/expiry times, authentication time, and optional nonce. Access tokens contain only issuer, subject, audience, issue/expiry times, client ID, and granted scope string. Tokens never automatically expose Administration state, raw AccessAssignment collections, unrestricted canonical attributes, provider observations, Credential/secret references, or raw internal Tenant/Identity/Principal IDs.

Signing private keys remain behind Platform `SigningKeyProvider`. `verificationKeys()` exposes public material only. JWKS requires the current signing key to be present, rejects duplicate verification key IDs, publishes current first, then retained verification keys deterministically. `FileSigningKeyProvider` uses configured additional verification public-key paths for overlap; this permits old-token verification during bounded key rotation without exposing retired private keys.

## Explicitly deferred extensions

The following are **not** enabled by the baseline and require separate accepted contracts: refresh tokens, introspection/revocation, RP-initiated logout, device authorization, confidential clients/client-secret authentication, client credentials, PAR, JAR, JARM, dynamic registration, token exchange, broad profile/email claim release, and concrete upstream OIDC/SAML first-party-session providers.

A framework feature is not a product feature. Each activation requires typed semantics, ownership, tenant isolation, secret handling, authorization, lifecycle/rotation, public contract/versioning where applicable, and negative tests.

## Verification requirements

Security coverage includes tenant derivation/isolation, inactive subject denial, authenticator revocation/revision invalidation, session fixation prevention, exact redirect validation, PKCE downgrade/replay, CSRF rejection, logout invalidation, governed-access fail-closed behavior, claim minimization, signing/JWKS consistency, retained-key rotation overlap, explicit federation-adapter registration, and secret non-disclosure.
