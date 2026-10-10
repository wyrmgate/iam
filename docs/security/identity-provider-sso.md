# Identity Provider and Single Sign-On Security Boundary

## Purpose

This document defines the implementation-facing first-party authentication, federation, and SSO boundary introduced by ADR-0042.

Authentication, SSO protocol issuance, business access governance, and IAM administrative authorization are separate decisions.

## Canonical ownership

- Identity owns governed subjects and Principal lifecycle.
- Credential owns authenticator/credential metadata and lifecycle for a Principal.
- Catalog owns Applications and typed SSO relying-party registration.
- Access/Governance own business access intent and policy decisions.
- Administration owns IAM/IdP administrative permission.
- Audit owns immutable data-minimized security evidence.
- Platform supplies protocol/session/token/signing infrastructure behind semantic ports.

Protocol framework entities must not become a competing canonical `User`, `Role`, `Permission`, `Application`, or `Credential` model.

## First-party authentication

A standalone deployment may authenticate an existing governed Principal through first-party authenticators. The concrete authenticator technology may evolve, but these invariants remain:

- the Principal and owning Identity must be eligible for login;
- authenticator lifecycle is Credential-owned;
- private verifier material never appears in ordinary APIs, browser storage, logs, audit, task payloads, or errors;
- verification is behind an explicit secret/verifier adapter;
- revocation/compromise must take effect at authentication/session boundaries without rewriting historical governance state.

The checkpoint-3 runtime already enforces these boundaries. Identity returns only a minimal authentication projection, Credential selects a tenant-bound active temporally valid authenticator, private verification is delegated by opaque `SecretReference`, and the resulting browser session is bound to the exact Credential identity/revision.

There is no permanent built-in `admin/admin`, hidden root credential, or reusable bootstrap password.

## Federated authentication

Wyrmgate may trust explicitly configured external providers in a later slice. Federation adapters must validate the upstream protocol and map the provider subject server-side to governed Principal/Identity state.

Provider groups, roles, scopes, tenant claims, and provider-native assurance names do not automatically become `AdministrativePermission` or business access.

## OIDC/OAuth baseline

The active first-party protocol baseline is OpenID Connect over OAuth with Authorization Code + mandatory S256 PKCE for public clients.

Implemented public surfaces when `iam.idp.enabled=true`:

- `GET /.well-known/openid-configuration`;
- `GET /oauth2/jwks`;
- `GET /oauth2/authorize`;
- `POST /oauth2/token`.

The implementation enforces exact redirect URI matching, bounded state/nonce handling, one-time short-lived authorization codes, and signed short-lived ID/access tokens. Refresh tokens are not issued.

Additional OAuth/OIDC features require explicit contracts before activation.

## Server-derived tenant routing

The browser must not authoritatively choose Tenant.

The public protocol request starts from a server-generated globally unique Catalog `client_id`. Catalog resolves that identifier to an active registration and server-derived `TenantContext`; the parent Application must also remain active.

The IdP then resolves the browser session inside that Tenant. No query parameter, state value, nonce, token claim, or browser-supplied header becomes authoritative Tenant input.

## Redirect and authorization response safety

A redirect URI is trusted only after exact string comparison with the Catalog registration. Wildcards, prefix matching, and normalization-based equivalence are prohibited.

An unknown/retired client or invalid redirect produces a direct error response. The server must never redirect an error to an unvalidated URI.

After an exact redirect has been validated, protocol errors may be sent to that URI. A valid caller `state` is echoed unchanged so the relying party can correlate the response; Wyrmgate does not interpret state as authority.

## PKCE and authorization codes

Public clients require `code_challenge_method=S256`.

Authorization codes are high-entropy, single-use, five-minute technical credentials:

- 256 bits of random raw code are generated;
- only the SHA-256 code digest is persisted;
- the stored row binds Tenant, Catalog registration/revision, Application, client ID, browser session, Principal/Identity, exact redirect URI, approved scopes, S256 challenge, optional nonce, authentication time, expiry, and consumption time;
- redemption atomically marks an unconsumed/unexpired row consumed before all secondary request bindings are checked;
- a bad verifier, wrong redirect, stale client revision, or replay therefore cannot leave the code reusable.

Raw authorization codes are never ordinary Audit/log/API/event data.

## State and nonce

`state` is a relying-party correlation/CSRF value. The IdP requires a bounded non-control-character value for this baseline, returns it only to the already validated redirect URI, and does not persist the raw state as authorization authority.

`nonce` is optional for the code-flow baseline. When supplied it is bounded, carried in the short-lived authorization transaction, and copied into the signed ID token. It is not Identity, Access, Administration, or Tenant authority.

## Application access decision

Authentication success does not imply permission to every Application.

If a Catalog SSO registration sets `requiresGovernedAccess=true`, code issuance pages that Application's active Catalog entitlements and queries Access `EffectiveAccess` for the authenticated Identity. The protocol layer consumes semantic reads only; it does not mutate Access or join Access/Catalog persistence.

Because this is a privilege-increase decision, evaluator failure fails closed and no code is issued. `requiresGovernedAccess=false` skips only this application gate.

## Subject and claim semantics

The current public `sub` is an opaque deterministic digest of the versioned subject-policy prefix plus server-derived Tenant ID and governed Identity ID. Raw internal IDs are not exposed as token claims.

Claims are curated protocol projections. Current ID tokens contain only issuer, subject, audience, issue/expiry times, authentication time, and optional nonce. Current access tokens contain only issuer, subject, audience, issue/expiry times, client ID, and granted scope string.

Tokens must never dump:

- Administration role/grant/delegation/elevation/break-glass state;
- raw AccessAssignment data;
- unrestricted Identity attributes;
- provider observations;
- Credential material or secret references;
- internal correlation/persistence identifiers not accepted as public protocol identifiers.

`profile` and `email` scope names do not by themselves authorize attribute disclosure. Typed claim-release policy is a separate explicit surface.

OAuth scopes do not create Wyrmgate AdministrativePermission.

## Browser and session security

Management-console and first-party login UI must use secure same-origin session handling. Long-lived bearer tokens, refresh tokens, client secrets, and authenticator material must not be placed in browser local/session storage or exposed in URLs.

The existing browser-session runtime uses a fresh 256-bit opaque token. Only a SHA-256 digest is persisted. Sessions are tenant-bound and store Principal/Identity plus the exact Credential ID/revision that established authentication. They carry no Administration permission, AccessAssignment, application entitlement, or OAuth scope.

Default bounds are 30 minutes idle and 8 hours absolute. Every resolution revalidates current Principal/Identity login eligibility plus Credential ownership, kind, lifecycle, temporal validity, and revision. Revocation/compromise/revision change invalidates the session fail closed.

Checkpoint 4 consumes an existing session via reserved cookie name `__Host-wyrmgate_sso` but does not yet expose public login/logout. Checkpoint 5 must establish the cookie with deployment-appropriate Secure, HttpOnly, and SameSite protection, prevent fixation, enforce CSRF on cookie-authenticated state-changing endpoints, and implement logout invalidation.

## Token signing and key management

Signing private keys remain behind the Platform `SigningKeyProvider`. Public JWK material is publishable; private material is never returned.

The current adapter supports RSA JCA signing algorithms mapped to JOSE names (`SHA256withRSA` -> `RS256`, etc.) so JWT headers, discovery, and JWKS are consistent with the actual signature primitive.

Key rotation must retain public verification material long enough for previously issued bounded-lifetime tokens. Publishing multiple retained verification keys is not yet implemented by checkpoint 4 and must be completed before claiming seamless overlapping-key rotation.

## Control-plane authentication

External bearer authentication from ADR-0011 remains supported. First-party Wyrmgate authentication is another source of provider-neutral governed actor context.

Regardless of source:

1. identify the authenticated Principal/provider subject;
2. resolve governed Identity and Tenant server-side;
3. establish provider-neutral authentication assurance;
4. perform Administration authorization at operation time.

A valid SSO token does not bypass Administration authorization.

## Initial administrator bootstrap

Burn-once bootstrap remains operator-controlled and default-deny. It may target an externally authenticated subject or an existing first-party Principal with an active login authenticator.

Bootstrap creates only explicit Administration authority. It does not create wildcard OAuth scopes or blanket application access.

## Audit and observability

Audit may record data-minimized semantic evidence for authentication, session lifecycle, client configuration, signing-key administration, federation mapping, and suspicious authenticator events.

Audit/logging must never include passwords, authorization codes, access/bearer tokens, refresh tokens, raw session tokens, client secrets, private keys, recovery secrets, or raw authenticator material. Protocol exceptions should expose stable error codes rather than secret-bearing diagnostics.

## Implementation checkpoints

1. protocol/security foundation and JWKS;
2. governed SSO client registration;
3. first-party local authentication and browser-session runtime;
4. **Authorization Code + PKCE, discovery, exact redirect validation, state/nonce handling, and signed token issuance (implemented here);**
5. public same-origin console sign-in/login/logout;
6. federation adapters;
7. optional protocol extensions only when explicitly accepted.

Each slice requires negative security coverage appropriate to its surface, including tenant routing/isolation, redirect validation, PKCE downgrade/replay, lifecycle invalidation, fail-closed governance evaluation, claim minimization, secret non-disclosure, and CSRF/session-fixation handling where applicable.
