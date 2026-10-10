# First-Party IdP / SSO Protocol Contracts

## Purpose

This document is the living implementation contract for ADR-0042. Wyrmgate is an IAM platform with first-party Identity Provider (IdP), Single Sign-On (SSO), and IGA capabilities.

IdP/SSO is a composed product service over the canonical capabilities. It does not create a ninth capability and no protocol-framework client/user/role object becomes authoritative IAM state.

## Current implementation checkpoint

The first-party IdP is disabled by default. When `iam.idp.enabled=true`:

- `iam.idp.issuer` is mandatory and must use HTTPS except loopback HTTP for local development;
- Platform signing must be enabled and supply a publishable RSA signing key;
- `GET /.well-known/openid-configuration`, `GET /oauth2/jwks`, `GET /oauth2/authorize`, and `POST /oauth2/token` are public protocol surfaces isolated from `/api/v1/**` control-plane authorization;
- the active baseline is Authorization Code with mandatory S256 PKCE for Catalog-managed public clients;
- authorization-code and access/ID-token lifetimes are five minutes by default;
- public browser login/logout is still a later checkpoint; this slice consumes an already-established Platform browser session and returns `login_required` when one is absent.

Catalog owns typed SSO client registration. Platform owns only short-lived protocol/session/signing state. Access remains authoritative for effective application access.

## Configuration

Example standalone configuration:

```properties
iam.idp.enabled=true
iam.idp.issuer=https://iam.example.test

iam.signing.enabled=true
iam.signing.key-id=wyrmgate-2026-01
iam.signing.key-algorithm=RSA
iam.signing.signing-algorithm=SHA256withRSA
iam.signing.private-key-path=/run/secrets/wyrmgate-idp-private.pem
iam.signing.public-key-path=/etc/wyrmgate/wyrmgate-idp-public.pem
```

The signing provider uses JCA algorithm names; discovery/JWKS/JWT headers expose the corresponding JOSE name (`SHA256withRSA` -> `RS256`, and likewise for the supported RSA SHA-384/SHA-512 variants).

Private-key bytes, passwords, authorization codes, bearer tokens, raw session tokens, refresh tokens, and client secrets are prohibited from ordinary logs, APIs, events, audit payloads, and browser storage.

## Discovery and JWKS

`GET /.well-known/openid-configuration` advertises only the implemented baseline:

- issuer;
- authorization endpoint;
- token endpoint;
- JWKS URI;
- `code` response type;
- `authorization_code` grant;
- public subjects;
- supported RSA ID-token signing algorithm;
- S256 code challenge;
- `openid`, `profile`, and `email` scopes;
- token endpoint authentication method `none`.

`GET /oauth2/jwks` publishes only public verification-key material. Current RSA-only JWKS support is an implementation checkpoint, not a canonical requirement.

The signing-key abstraction may retain old public verification material for overlap, but this checkpoint publishes only the current signing JWK. Broader rotation publication is a later bounded change.

## Governed client registration

`SsoClientRegistration` is Catalog-owned authoritative configuration and references exactly one Application. Multiple registrations may reference one Application.

The public-client baseline has these invariants:

- `clientType=PUBLIC`;
- no client secret exists;
- client IDs are server-generated public identifiers;
- client IDs are globally unique across tenants because unauthenticated protocol routing must derive Tenant server-side from `client_id`;
- S256 PKCE is mandatory;
- redirect URIs use exact string registration and exact matching; wildcard matching is prohibited;
- production redirects require HTTPS; loopback HTTP is permitted for local/native development;
- `openid` is mandatory and `profile`/`email` are the only additional baseline scopes;
- lifecycle is `ACTIVE -> RETIRED`, with retirement terminal;
- protocol resolution requires both the registration and parent Application to remain `ACTIVE`;
- mutation APIs remain tenant-bound and revision-controlled.

Catalog exposes a semantic `SsoClientProtocolQuery` for unauthenticated protocol routing. The protocol layer does not query Catalog tables directly. Browser request parameters never select the authoritative Tenant.

The existing SSO-client management endpoints remain:

- `GET/POST /api/v1/applications/{applicationId}/sso-clients`;
- `GET/PUT /api/v1/sso-clients/{registrationId}`;
- `POST /api/v1/sso-clients/{registrationId}/retire`.

Their runtime machine-readable description is provided by the server OpenAPI surface (`/api/openapi`). A separate checked-in `contracts/openapi/sso-client-v1.json` file is not currently present and must not be cited as an existing artifact.

## Authorization endpoint

`GET /oauth2/authorize` accepts the baseline parameters:

- `response_type=code`;
- `client_id`;
- `redirect_uri`;
- space-delimited `scope`;
- mandatory `state`;
- `code_challenge`;
- `code_challenge_method=S256`;
- optional `nonce`.

Processing order is security-significant:

1. resolve the active client and server-derived Tenant;
2. validate `redirect_uri` by exact match against that registration;
3. only after the redirect is trusted, validate response type, scopes, state, nonce, and PKCE;
4. resolve and revalidate the existing browser session in the derived Tenant;
5. if `requiresGovernedAccess=true`, query current Catalog/Access semantics and fail closed when the evaluator is unavailable;
6. persist only short-lived code state and return the raw code once in the trusted redirect.

Unknown/retired clients and unregistered redirect URIs return a direct error response and never redirect to attacker-controlled input. Errors discovered after redirect validation may be returned to that exact redirect URI together with the caller's valid state.

`state` is treated as an opaque client correlation/CSRF value. Wyrmgate validates a bounded non-control-character value and echoes it exactly; it does not reinterpret state as Tenant or authority.

`nonce` is optional for this authorization-code baseline. When supplied, it is bounded and carried through the authorization-code transaction into the signed ID token. It is not IAM authority.

## Authorization-code state

Platform persists short-lived technical authorization-code state in `platform.idp_authorization_code`.

The raw authorization code is generated from 256 bits of randomness and is never persisted. Persistence contains only its SHA-256 digest plus the protocol bindings required to redeem it safely:

- server-derived Tenant;
- Catalog SSO registration ID and revision;
- Application ID and client ID;
- browser session ID;
- Principal and Identity IDs needed for the protocol subject;
- exact redirect URI;
- approved scopes;
- S256 challenge;
- optional nonce;
- authentication time;
- creation, expiry, and one-time consumption timestamps.

Catalog/Identity/Access references are stable cross-capability IDs, not database-level ownership transfers.

Redemption performs an atomic `UPDATE ... RETURNING` against an unconsumed, unexpired digest. A code is burned before all request bindings are checked, so a failed replay/binding/PKCE attempt cannot leave that code reusable.

## Token endpoint

`POST /oauth2/token` consumes `application/x-www-form-urlencoded` with:

- `grant_type=authorization_code`;
- `code`;
- `client_id`;
- `redirect_uri`;
- `code_verifier`.

Public clients do not authenticate with a client secret.

Redemption requires:

- an active client resolved to the same server-derived Tenant;
- an unexpired, previously unconsumed code;
- unchanged registration identity/revision and client ID;
- the exact redirect URI used at authorization time and still registered;
- an RFC 7636 verifier length/character set;
- constant-time comparison of the computed S256 challenge with the stored challenge.

Successful token responses contain `access_token`, `token_type=Bearer`, `expires_in`, `id_token`, and the granted scope string. Responses use `Cache-Control: no-store` and `Pragma: no-cache`. Refresh tokens are not issued.

## Subjects and token claims

The current public subject policy is deterministic and opaque:

`base64url(SHA-256("wyrmgate-sub-v1|" + tenantId + "|" + identityId))`

This keeps raw internal IDs out of the token while producing a stable public subject within the current policy. Changing the subject policy is a public protocol compatibility change and requires an explicit contract decision.

ID-token claims are deliberately minimal:

- `iss`;
- `sub`;
- `aud` = client ID;
- `iat`;
- `exp`;
- `auth_time`;
- optional `nonce`.

Access-token claims are deliberately minimal:

- `iss`;
- `sub`;
- `aud` = client ID;
- `iat`;
- `exp`;
- `client_id`;
- granted `scope`.

The presence of `profile` or `email` scope in this checkpoint does not cause unrestricted Identity attributes to be copied into tokens. Typed claim release is a separate explicit policy surface.

Tokens must never automatically expose AdministrativeRole/Grant/Delegation/Elevation/BreakGlass state, raw AccessAssignment collections, unrestricted canonical attributes, provider observations, credential/secret references, raw Tenant/Identity/Principal database IDs, or framework-native authorities.

OAuth scopes are protocol grants for a relying party; they do not become Wyrmgate `AdministrativePermission`.

## Application access gate

Where `requiresGovernedAccess=true`, authorization-code issuance evaluates the application's current active Catalog entitlements against Access `EffectiveAccess` through semantic query ports.

The IdP adapter pages Catalog data and calls the Access query; it never joins or mutates another capability's tables. Any runtime failure in this mandatory privilege-increase evaluation fails closed and no authorization code is persisted.

`requiresGovernedAccess=false` bypasses only the Application-specific business-access gate. It does not bypass Identity/Principal/session eligibility, client/redirect validation, PKCE, scopes, token signing, or Administration authorization elsewhere.

## Browser session boundary

Checkpoint 3 remains the browser authentication source for checkpoint 4:

- session establishment uses a fresh 256-bit opaque token;
- only the SHA-256 digest is persisted;
- sessions are tenant-bound with default 30-minute idle and 8-hour absolute limits;
- each resolution revalidates current Identity/Principal eligibility and the exact establishing Credential revision;
- session context contains no Administration permission, AccessAssignment, application entitlement, or OAuth scope authority.

The authorization endpoint currently looks for the reserved cookie name `__Host-wyrmgate_sso`, but checkpoint 4 does not create that cookie or activate public login/logout. Cookie attributes, first-party login interaction, CSRF-protected state-changing browser endpoints, and logout HTTP behavior are checkpoint 5.

## Verification requirements

Every implementation slice must include negative coverage appropriate to the surface, including:

- server-derived tenant isolation;
- unknown/retired client rejection;
- exact redirect matching before any redirect response;
- PKCE downgrade/bypass/replay attempts;
- browser-session revalidation;
- mandatory governed-access fail-closed behavior;
- token/claim over-disclosure;
- signing/JWKS algorithm consistency;
- private key/client secret/password/code/token leakage;
- disabled IdP surfaces remaining closed.

## Deferred protocol features

Refresh tokens, token revocation/introspection, device authorization, client credentials, confidential-client authentication, PAR/JAR/JARM, dynamic client registration, token exchange, federation adapters, and typed profile/email claim release require explicit contracts before activation.
