# Authentication, Identity Provider and SSO Security Boundary

## Purpose

This document is the implementation-facing security contract for ADR-0042. Wyrmgate is an enterprise IAM platform with Identity Governance and Administration plus a first-party Authentication/Identity Provider/Single Sign-On capability.

Authentication answers **how a governed subject proves identity and obtains an SSO/session/protocol result**. Access and Governance answer **what business access should exist**. Administration answers **what an authenticated governed actor may do to Wyrmgate itself**. These decisions are intentionally separate.

## Ownership

Authentication owns tenant-bound:

- local login bindings/aliases;
- browser authentication/SSO sessions;
- OIDC/OAuth client registrations;
- authorization-code and refresh-grant protocol state;
- stable OIDC subject mappings;
- federation routing and session policy;
- claim-release policy.

Authentication references governed Identity/Principal IDs but does not mutate Identity. It asks Credential through a semantic authenticator-verification contract; it never reads raw password hashes, private keys, API keys, recovery secrets or external secret values directly.

## Tenant isolation

Every login binding, session, client registration, authorization/consent record and Wyrmgate-issued subject is tenant-bound. Tenant is derived from trusted server-side routing/session/client context, never from an unconstrained caller-writable header or token claim.

A browser SSO session is not cross-tenant authority. Moving between tenant security contexts requires a new explicit Authentication decision.

## First supported protocol

The first first-party SSO target is OpenID Connect 1.0 over OAuth authorization-server semantics with:

- Authorization Code flow;
- PKCE using `S256` for public clients;
- exact registered redirect-URI matching;
- public discovery metadata;
- public JWKS verification keys;
- signed ID/access tokens using Platform-owned signing adapters;
- explicit token/session revocation semantics;
- curated, data-minimized claims.

The implicit grant and Resource Owner Password Credentials grant are not supported. SAML is not claimed by this slice.

## Client registration

OIDC/OAuth clients are Authentication-owned authoritative resources. A client registration records, at minimum:

- tenant ID;
- immutable client identifier;
- display name;
- public/confidential classification;
- exact redirect URIs;
- exact post-logout redirect URIs when logout is enabled;
- allowed grant types and response types;
- allowed protocol scopes;
- claim-release policy reference/configuration;
- lifecycle state and optimistic revision.

Public clients have no client secret. Confidential client credentials, when enabled, use a Credential/secret-provider boundary and remain write-only/redacted through ordinary administration APIs.

## Login bindings

A local login identifier is not Identity itself. `AuthenticationLoginBinding` binds one normalized tenant-local login identifier to one governed Principal and Identity. Login identifiers are unique within a tenant and can be disabled independently from the Principal without mutating Identity state.

Authentication must confirm that the target Principal/Identity remain eligible before establishing a new session or issuing fresh tokens. Suspension/deactivation and credential compromise/revocation fail closed for new authentication/issuance according to current policy.

## Authenticator verification

Authentication never obtains a stored password hash or secret value through a repository. It calls a Credential-owned semantic verifier with a Principal/credential context and transient presented secret. The verifier returns only a normalized result/assurance outcome needed by Authentication.

Presented secrets are transient request material. They must not be logged, audited, placed in URLs, stored in Authentication tables, or included in error payloads.

## Sessions and bearer-equivalent artifacts

Authentication sessions, authorization codes and refresh grants are sensitive security state.

- Browser session identifiers are unpredictable and stored only in protected form where persistence is required.
- Browser cookies are `Secure`, `HttpOnly`, and use an explicit `SameSite` policy; state-changing same-origin browser endpoints require CSRF protection.
- Authorization codes are single-use and short-lived.
- Refresh grants are revocable and rotated/replay-aware when enabled.
- Raw session identifiers, authorization codes and refresh tokens never appear in ordinary admin APIs, audit events, logs or telemetry.
- Access/ID tokens are returned only through standards-defined protocol endpoints, not administrative resource APIs.

## Subject identifiers and claims

Wyrmgate-issued OIDC `sub` is opaque and stable for the configured Authentication subject policy. Mutable usernames, email addresses and raw database IDs are not used as security authority.

Claim release is allow-listed and data-minimized. Protocol/security claims may include issuer, subject, audience, issuance/expiry, authentication time, nonce, tenant context and normalized assurance context. Governed profile attributes require explicit claim-release policy; provider-native attributes are not copied through automatically.

OAuth/OIDC scopes and claims, including external provider roles/groups/scopes, never directly create or imply:

- `AdministrativePermission`;
- `AdministrativeGrant` or `AdministrativeRole`;
- `AccessAssignment`;
- Governance approval/review authority.

## Control-plane use

ADR-0011 external JWT resource-server authentication remains supported. ADR-0042 adds Wyrmgate-issued authentication as another trusted transport source.

Regardless of token issuer, Wyrmgate control-plane operations still resolve the authenticated subject to a governed actor and invoke Administration's current operation-time authorization evaluator. Authentication is never the final authorization source for IAM administrative mutation.

## Signing and key rotation

Public verification material may be exposed through JWKS. Private signing material stays behind the Platform signing/HSM adapter and is never persisted in Authentication domain tables or returned by APIs.

Key rotation preserves a bounded overlap for verification of previously issued tokens. The current signing key is selected server-side; callers never choose an arbitrary signing key.

## Administrative permissions

The first finite permissions are:

- `authentication-client:read`
- `authentication-client:create`
- `authentication-client:update`
- `authentication-client:disable`
- `authentication-login-binding:read`
- `authentication-login-binding:create`
- `authentication-login-binding:update`
- `authentication-login-binding:disable`
- `authentication-session:read`
- `authentication-session:revoke`

There is no `authentication:*` wildcard. Permissions are evaluated through Administration scopes and tenant isolation like other control-plane permissions.

## Standalone bootstrap

There is no built-in `admin/admin` or permanent root credential.

A standalone deployment may use an explicit one-shot operator bootstrap to establish an initial local login binding/authenticator for an already eligible governed Identity/Principal and create only the finite initial Administration authority defined by the bootstrap contract. Secret material is operator-supplied and must cross directly into the Credential secret boundary; it must not be stored as plain Authentication/bootstrap state or echoed in output.

The burn-once tenant marker remains permanent. Losing all administrators does not reopen bootstrap.

## Failure behavior

Authentication fails closed for:

- unknown/disabled login binding;
- ineligible Identity/Principal;
- revoked/expired/compromised authenticator;
- failed authenticator verification;
- unregistered or mismatched redirect URI;
- invalid/replayed/expired authorization code;
- invalid PKCE verifier;
- disabled client;
- invalid tenant/client/session relationship;
- unavailable mandatory security evaluation.

Errors remain semantic and data-minimized. They do not reveal whether a username exists, secret-reference details, credential hashes, token contents, signing keys or provider exception internals.

## Audit boundary

Authentication emits curated security facts/evidence such as login success/failure category, session established/revoked, client lifecycle changes and token/authorization lifecycle outcomes where required. Audit receives stable IDs, actor/resource references, outcome, correlation/causation and minimized security context only. Raw secrets, codes, tokens and session identifiers are excluded.