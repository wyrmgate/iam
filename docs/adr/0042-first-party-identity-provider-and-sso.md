# ADR-0042: First-party identity provider and single sign-on

- Status: Accepted
- Date: 2026-10-06
- Supersedes: the external-IdP-only authentication boundary of ADR-0011; ADR-0011 remains authoritative for governed actor binding, separation of authentication from Administration authorization, and burn-once initial-administrator bootstrap unless amended below.

## Context

Wyrmgate has so far been described primarily as an enterprise Identity Governance and Administration platform. ADR-0011 selected externally issued OAuth/OIDC bearer tokens as the first control-plane authentication transport and deliberately kept external identity-provider claims separate from Wyrmgate AdministrativePermission.

The product scope is now broader: Wyrmgate is an enterprise IAM platform that must be usable as an Identity Provider (IdP) and Single Sign-On (SSO) service as well as an IGA system. A standalone Wyrmgate deployment must be able to authenticate governed principals itself, establish browser SSO sessions, issue standards-based tokens to registered applications, and optionally federate external identity providers without making those providers the source of Wyrmgate governance authority.

This change is architectural because authentication-session/token authority, local authenticators, relying-party registration and claim release become first-class product concerns. It must not create a duplicate User aggregate, a second authorization model, or a universal superuser.

## Decision

### Product scope

Wyrmgate is an enterprise Identity and Access Management platform combining:

- canonical Identity and Principal management;
- first-party authentication and federation;
- OpenID Connect / OAuth SSO and token issuance;
- Identity Governance and Administration;
- access lifecycle, provisioning, reconciliation and credential governance;
- scoped IAM administration and auditable security controls.

OIDC/OAuth is therefore a supported product surface, but it does not replace IGA. Governance still decides business access intent; SSO/runtime authorization consumes governed state through explicit semantic contracts.

### No new generic User aggregate

The existing canonical model remains authoritative:

- `Identity` is the governed subject;
- `Principal` is the technical account/representation used to authenticate or realize access;
- `Credential` belongs to a Principal and owns authenticator metadata/lifecycle;
- `Application` remains the governed application concept;
- `AccessAssignment` remains authoritative business access intent;
- Administration remains the only owner of Wyrmgate control-plane authority.

First-party SSO must not introduce a parallel `User`, `SsoUser`, generic account object, or OAuth-role model that competes with those concepts.

### SSO protocol service as a composed platform service

IdP/SSO is a product service composed from existing capability ownership rather than a new cross-cutting authoritative aggregate.

- Identity owns governed subject and Principal lifecycle.
- Credential owns local authenticator lifecycle and secret/verifier references.
- Catalog owns governed applications and their SSO-facing registration metadata through explicit typed contracts.
- Access and Governance own whether an Identity is entitled to use an application where policy requires governed assignment.
- Administration owns permission to configure and operate IdP/SSO administration.
- Audit owns immutable security evidence.
- Platform provides replaceable protocol/session/token/signing infrastructure behind semantic ports.

The protocol implementation may use an OAuth/OIDC framework, but framework types are not canonical domain concepts.

### Supported first-party protocol baseline

The first product baseline is OpenID Connect over OAuth 2.x using Authorization Code with PKCE for browser/public clients and confidential-client authentication for server-side clients where configured.

The baseline shall provide:

- OIDC discovery metadata;
- public JWKS for active/retained verification keys;
- authorization endpoint;
- token endpoint;
- ID tokens and access tokens signed by Wyrmgate-managed signing keys;
- nonce/state/PKCE validation according to protocol requirements;
- browser SSO session establishment and termination;
- exact registered redirect-URI validation;
- explicit client/application registration through governed administrative contracts;
- bounded token/session lifetimes and key rotation;
- data-minimized claims with explicit release policy.

Refresh tokens, token revocation, introspection, RP-initiated logout, device authorization, client credentials, PAR/JAR/JARM, dynamic client registration and other extensions are explicit contract choices and must not be assumed merely because a framework supports them.

### Local authentication and federation

A deployment may authenticate a Principal through one or more governed authentication methods:

1. **First-party local authentication** backed by Credential-owned authenticators and a secret/verifier provider boundary.
2. **Federated authentication** using an explicitly configured upstream OIDC/SAML or other accepted authentication provider adapter.

Federated provider identities are mapped server-side to a governed Principal/Identity. Upstream groups, roles, scopes, tenant claims and provider-native assurance strings do not become Wyrmgate business access or AdministrativePermission automatically.

A first-party password verifier, WebAuthn credential, private key or other secret authentication material is never exposed through ordinary Credential APIs, events, logs, URLs, audit payloads or console storage. The Credential capability owns the lifecycle contract; secret/private material remains behind an explicit secret/verifier adapter.

### Subject and claim semantics

Wyrmgate-issued OIDC `sub` is a stable, non-secret protocol subject identifier derived from the governed Principal/Identity mapping according to a documented subject policy. It must not expose database implementation keys unless that choice is explicitly accepted as the public subject contract.

Token claims are curated protocol projections, not authoritative copies of IAM state. Standard claims and application-specific claims are released only through explicit typed policy. Provider-native source data remains observation until mapped into governed canonical state.

Administrative roles/grants, raw AccessAssignment records, credential material, provider observations, secret references and unrestricted canonical attributes are never dumped wholesale into tokens.

### SSO authorization is not IAM administrative authorization

A valid Wyrmgate-issued token proves authentication and carries only the claims/scopes intentionally released for its audience. OAuth scopes and OIDC claims do not become Wyrmgate AdministrativePermission.

Control-plane requests continue to resolve a governed actor and are authorized by Administration at operation time. The browser/SSO layer may use a backend-derived effective-authority projection to optimize navigation, but the server remains authoritative.

Likewise, an application's runtime authorization must not be inferred from AdministrativeGrant. Business/application access uses Catalog/Access/Governance semantics and, where required, current AccessAssignment/effective-access state.

### Control-plane authentication modes

ADR-0011's external JWT resource-server mode remains a supported deployment mode, but it is no longer the only canonical authentication source.

Wyrmgate may authenticate its own control-plane console through its first-party SSO service. In that mode, the server derives the same provider-neutral governed actor context from the authenticated Wyrmgate Principal/Identity rather than trusting browser-supplied tenant or authority claims.

External federation remains supported and may feed the same governed actor-resolution boundary.

### Initial administrator bootstrap

There is still no permanent `admin/admin`, hidden root account, wildcard superuser, or reusable bootstrap password.

A fresh tenant uses the burn-once initial-administrator bootstrap semantics from ADR-0011, amended so the bootstrap target may bind either:

- an existing externally authenticated subject, or
- an existing first-party governed Principal capable of authenticating through an active Credential.

Bootstrap creates explicit Administration authority only. It does not grant blanket application access, generate a universal OAuth scope, or bypass ordinary future authorization checks.

### Sessions, tokens and secrets

SSO browser sessions, authorization codes, refresh tokens if later enabled, client secrets, signing private keys and credential verifier material are security-sensitive technical state. They are never ordinary domain events or generic audit payloads.

Opaque session/token state must support expiry and revocation. Signing private keys remain behind the Platform signing boundary. Public JWK material is publishable by design; private signing material is never returned by an API.

### Audit and observability

Audit receives data-minimized semantic security evidence for relevant events such as successful/failed authentication, session establishment/termination, client configuration changes, token-security administration, federation mapping changes and suspicious/revoked-authenticator events where policy requires it.

Audit records never contain passwords, authorization codes, bearer tokens, refresh tokens, private keys, raw client secrets or raw authenticator material. Operational logs follow the same secret boundary.

## Consequences

- Wyrmgate is no longer accurately described as only an IGA platform; the canonical product description is IAM + IdP/SSO + IGA.
- ADR-0011 remains valid for security separation and bootstrap but is superseded where it implied external IdP authentication was the sole canonical control-plane model.
- First-party local authentication and OIDC token issuance require new explicit interfaces, persistence and tests rather than reuse of provider claims as authority.
- Catalog/Application administration will need typed SSO client registration contracts; raw OAuth framework client entities must not leak into the canonical domain.
- Credential must gain local-authenticator verifier operations without exposing secret material.
- The console can authenticate through same-origin first-party SSO while keeping browser tokens/secrets out of local/session storage.
- Formal v0.8 BRD/FRD/SRS/DDD/SAD/Security/Integration/RTM are amended by this ADR until the next controlled formal revision folds the broader IAM/IdP/SSO product scope into the package.

## Rejected alternatives

- **Keep Wyrmgate IGA-only and require Keycloak/Okta/Auth0/Entra for all authentication:** contradicts the standalone IAM/IdP/SSO product requirement.
- **Create a separate SSO user database unrelated to Identity/Principal:** duplicates governed subject semantics and creates correlation/ownership ambiguity.
- **Treat OAuth scopes or upstream groups as Wyrmgate administrative roles:** creates a competing authorization authority outside Administration.
- **Store raw passwords/client secrets in ordinary Credential tables or APIs:** violates the credential secret boundary.
- **Use one permanent built-in superadmin to solve standalone bootstrap:** creates an enduring bypass and conflicts with default-deny scoped administration.
- **Issue tokens containing the full Identity/Access graph:** violates data minimization and turns protocol projections into accidental authority/state replication.
