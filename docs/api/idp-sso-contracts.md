# First-Party IdP / SSO Protocol Contracts

## Purpose

This document is the living implementation contract for ADR-0042. Wyrmgate is an IAM platform with first-party Identity Provider (IdP), Single Sign-On (SSO), and IGA capabilities.

This contract does not create a ninth canonical domain capability. IdP/SSO is a composed product service over Identity, Principal, Credential, Catalog, Access/Governance, Administration, Audit, and Platform-owned protocol/session/signing infrastructure.

## Current implementation checkpoint

The first-party IdP foundation is fail-closed and disabled by default.

When `iam.idp.enabled=true`:

- `iam.idp.issuer` is mandatory;
- the issuer must use HTTPS, except loopback HTTP for local development;
- Platform signing must be enabled and supply a publishable active RSA signing key;
- `GET /oauth2/jwks` is public and returns only public verification-key material for the active signing key;
- private signing material is never exported through the IdP API;
- the IdP public endpoint has its own security chain and does not weaken `/api/v1/**` control-plane authorization.

Catalog now also owns typed public-client registration used by the forthcoming authorization-server adapter:

- multiple SSO client registrations may reference one Application;
- each registration has a server-generated public `clientId` distinct from its internal registration ID;
- initial client type is `PUBLIC`; no client secret exists in this slice;
- S256 PKCE is mandatory for this client type;
- exact redirect URIs are stored as child rows, never wildcard patterns or arbitrary JSON;
- production redirects require HTTPS; loopback HTTP is permitted for development/native-loopback use;
- baseline scopes are `openid`, `profile`, and `email`, with `openid` mandatory;
- `requiresGovernedAccess` defaults to `true` and is an explicit typed configuration input;
- lifecycle is `ACTIVE -> RETIRED`, with retirement terminal;
- mutation APIs use Administration permissions, ETag/If-Match revisions, Idempotency-Key, Audit evidence and tenant-bound persistence;
- list APIs use signed tenant/application-bound cursors.

The management contract is `contracts/openapi/sso-client-v1.json` and exposes:

- `GET/POST /api/v1/applications/{applicationId}/sso-clients`;
- `GET/PUT /api/v1/sso-clients/{registrationId}`;
- `POST /api/v1/sso-clients/{registrationId}/retire`.

The OIDC discovery document is deliberately **not** published yet because Authorization Code + PKCE and token endpoints are not active. Wyrmgate must not advertise protocol endpoints that do not exist. Discovery becomes public atomically with the authorization-server endpoint slice.

Current RSA-only JWKS support is an implementation checkpoint, not a canonical requirement. Additional signing algorithms require compatible signing-port and verification-key support plus negative tests before activation.

## Configuration

Example standalone-development foundation configuration:

```properties
iam.idp.enabled=true
iam.idp.issuer=https://iam.example.test

iam.signing.enabled=true
iam.signing.key-id=wyrmgate-2026-01
iam.signing.key-algorithm=RSA
iam.signing.signing-algorithm=RS256
iam.signing.private-key-path=/run/secrets/wyrmgate-idp-private.pem
iam.signing.public-key-path=/etc/wyrmgate/wyrmgate-idp-public.pem
```

Private-key paths are deployment configuration, not API-visible metadata. Private key bytes, passwords, authorization codes, bearer/refresh tokens and raw client secrets are prohibited from ordinary logs, APIs, events, audit payloads and browser storage.

## Protocol target

The accepted first-party baseline is OpenID Connect over OAuth with Authorization Code + PKCE. The complete baseline will expose:

- `/.well-known/openid-configuration`;
- `/oauth2/jwks`;
- `/oauth2/authorize`;
- `/oauth2/token`;
- secure browser login/session establishment;
- exact registered redirect-URI validation;
- S256 PKCE;
- nonce/state protocol handling;
- signed ID/access tokens with bounded lifetime;
- explicit session termination/logout.

No endpoint is considered implemented merely because a framework can auto-enable it.

## Governed client registration

SSO relying-party registration is governed configuration, not generic framework CRUD.

### Ownership and cardinality

- Catalog continues to own `Application`.
- `SsoClientRegistration` is Catalog-owned authoritative configuration referencing exactly one existing Application.
- An Application may have multiple registrations so web, SPA, desktop/native, or other distinct relying-party clients do not need to share protocol identifiers or redirect sets.
- A protocol framework `RegisteredClient` or equivalent is a derived adapter/projection and may never become a competing source of truth.

### Initial public-client baseline

The first registration slice supports public clients only:

- `clientType=PUBLIC`;
- `pkceS256Required=true`;
- no client secret is accepted, generated, persisted or returned;
- `clientId` is server-generated and is public protocol metadata, not a secret;
- redirect URIs use exact string registration and exact matching; wildcard matching is prohibited;
- non-loopback HTTP redirect URIs are rejected;
- `openid` is mandatory and `profile`/`email` are the only additional baseline scopes.

Confidential-client authentication is intentionally deferred until its secret ownership and lifecycle are defined without violating the canonical invariant that Credential belongs to Principal. Framework convenience is not sufficient authority to invent a second secret/credential model.

### Governance and concurrency

Administration authorizes registration management at operation time through:

- `sso-client:read`;
- `sso-client:create`;
- `sso-client:update`;
- `sso-client:retire`.

These permissions are not automatically added to the burn-once initial administrator role. Administrators must establish explicit governed authority using the existing Administration model.

Mutable SSO registrations use revision-based optimistic concurrency. Retryable create/update/retire operations require `Idempotency-Key`. Retirement is terminal and removes the registration from the active protocol query. Audit receives only semantic, data-minimized success/denied/failure evidence; no protocol secret exists in the public-client slice.

### Protocol adapter query

The future authorization-server adapter resolves a client by `(Tenant, clientId)` through a Catalog semantic query that returns only `ACTIVE` registrations. It must not query or mutate Catalog tables directly and must not treat a retired client as protocol-valid even if stale framework state exists.

## Authentication and session boundary

First-party authentication must resolve an existing governed Principal and its owning Identity. Credential owns local authenticator lifecycle and a secret/verifier adapter performs private verification.

A successful authentication establishes provider-neutral actor/session context. It does not create Administration permission, AccessAssignment, application entitlement or OAuth scope by itself.

Browser sessions must be cookie based with deployment-appropriate `Secure`, `HttpOnly`, and `SameSite` protections, CSRF protection for cookie-authenticated mutations, session fixation protection, bounded idle/absolute lifetime and logout invalidation. Long-lived bearer/refresh tokens are not stored in browser local/session storage.

## Claims and subjects

OIDC/OAuth claims are audience-specific curated projections.

They must never automatically expose or serialize:

- AdministrativeRole/Grant/Delegation/Elevation/BreakGlass state;
- raw AccessAssignment collections;
- unrestricted canonical attributes;
- provider observations;
- credential/secret references;
- internal persistence identifiers that have not been accepted as public protocol identifiers.

OAuth scopes and upstream groups/roles/claims never become Wyrmgate `AdministrativePermission` automatically.

## Application access gate

Where `requiresGovernedAccess=true`, authorization-code issuance queries current Catalog/Access/Governance semantics through explicit ports. Protocol infrastructure consumes the semantic decision; it does not mutate Access authority or infer application access from authentication claims.

Required privilege-increase evaluation fails closed when its authoritative evaluator is unavailable.

`requiresGovernedAccess=false` means authentication eligibility is still required but an Application-specific AccessAssignment gate is not required by this registration. It does not bypass Administration authorization, Identity/Principal lifecycle eligibility, authentication assurance, client validation, PKCE or claim-release policy.

## Bootstrap

There is no default `admin/admin`, permanent superadmin password, hidden root account or wildcard superuser.

Burn-once initial-administrator bootstrap remains the only initial authority establishment mechanism. ADR-0042 permits that bootstrap to target an existing first-party Principal with an active login authenticator as well as an external provider subject.

## Verification requirements

Every implementation slice must include negative tests for:

- tenant isolation;
- exact redirect URI matching;
- PKCE downgrade/bypass attempts;
- unregistered or retired clients;
- inactive Principal/Identity login;
- revoked/compromised authenticator use;
- token/claim over-disclosure;
- conversion of OAuth/upstream claims into Administration authority;
- private key/client secret/password/code/token leakage;
- session fixation/CSRF where applicable;
- disabled IdP surfaces remaining closed.
