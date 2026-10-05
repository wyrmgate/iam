# ADR-0042: First-party Authentication, Identity Provider and SSO capability

- Status: Accepted
- Date: 2026-10-05

## Context

The v0.8 controlled specifications and ADR-0001 describe Wyrmgate primarily as an Identity Governance and Administration platform, while ADR-0011 deliberately chose externally issued OAuth/OIDC bearer tokens as the first control-plane authentication transport. That baseline is too narrow for the intended Wyrmgate product: Wyrmgate must also operate as an enterprise IAM Identity Provider and Single Sign-On system rather than requiring a separate IdP for every deployment.

Adding first-party login and token issuance is not an implementation detail inside Credential or Administration. An IdP owns durable security state and protocol semantics of its own, including authentication sessions, relying-party/client registrations, authorization grants/codes, stable subject identifiers, federation policy and claim-release policy. Folding that state into Credential would make an authentication instrument own protocol/session authority; folding it into Administration would conflate authentication with permission to operate Wyrmgate itself.

At the same time, the existing security separation remains essential: authenticating a subject, issuing an OAuth/OIDC token, governing business access, and authorizing a Wyrmgate administrative operation are different decisions. OAuth scopes, OIDC claims, external provider groups and SSO application roles must not become an alternate path to `AdministrativePermission` or `AccessAssignment` authority.

## Decision

### Product scope

Wyrmgate is an enterprise Identity and Access Management platform that includes both:

1. Identity Governance and Administration capabilities; and
2. a first-party Authentication capability able to operate as an OpenID Connect Identity Provider / OAuth authorization server and Single Sign-On authority.

External identity providers remain supported. A deployment may use first-party authentication, federated authentication, or an explicitly configured combination without changing governed Identity, Access or Administration semantics.

The first standards-based SSO protocol is OpenID Connect 1.0 on OAuth authorization-server semantics. SAML, WS-Federation and other protocols are not implied by this ADR and require separate accepted scope/contracts before being claimed as implemented.

### Canonical Authentication capability

`Authentication` becomes a canonical logical capability alongside Identity, Catalog, Access, Governance, Credential, Integration, Administration and Audit. As with every capability, it is not inherently a package, Maven module, process, service or database schema.

Authentication owns authoritative state and policy for:

- first-party and federated authentication sessions;
- local login bindings/aliases that resolve a tenant-scoped login identifier to a governed Principal/Identity;
- OIDC/OAuth client registrations and redirect-URI/grant/claim-release configuration;
- stable Wyrmgate-issued OIDC subject mappings;
- authorization-code, refresh-grant, consent/authorization and revocation state needed by supported protocol flows;
- authentication/federation routing policy and SSO session policy;
- protocol-facing claim-release policy.

Authentication does **not** own Identity lifecycle, Principal lifecycle, credential/private authenticator material, business access assignments, governance decisions, Wyrmgate administrative grants, provider provisioning or audit evidence.

### Cross-capability boundaries

- **Identity** remains owner of the governed `Identity` and technical `Principal`. Authentication references them by stable ID and must re-evaluate current eligibility for security-sensitive issuance/session use.
- **Credential** remains owner of `Credential` lifecycle and opaque private-material references. Authentication asks Credential through a semantic verification contract to verify an authenticator; Authentication never reads raw password hashes, private keys, client secrets, recovery secrets or provider secret material.
- **Catalog** remains owner of business `Application` / `ApplicationTarget`. An Authentication-owned OIDC client may reference a Catalog application but protocol registration state is not stored by Catalog.
- **Access/Governance** remain owners of business access intent and governance decisions. OAuth/OIDC scope or claim issuance is not an `AccessAssignment` mutation unless an explicit governed mapping is separately defined.
- **Administration** remains owner of permission to operate Wyrmgate. Login/session/token success grants no Wyrmgate administrative permission by itself.
- **Audit** receives curated, data-minimized authentication/security evidence. Raw credentials, authorization codes, refresh tokens, session secrets and private keys are never Audit payloads.
- **Platform** may provide clocks, IDs, signing/HSM adapters, secure random generation, HTTP/session infrastructure and persistence mechanics without owning Authentication business/security semantics.

Only Authentication mutates Authentication-owned authoritative state.

### Tenant and subject isolation

Tenant remains the hard isolation boundary.

Every first-party login binding, authentication session, client registration, consent/authorization record and Wyrmgate-issued subject is tenant-bound. Client-supplied tenant identifiers are not trusted as isolation authority. A session or authorization established for one tenant must not be reusable to cross into another tenant without a new explicit tenant-bound authentication decision.

Wyrmgate-issued OIDC `sub` values are opaque, stable for the selected subject policy, and must not expose mutable usernames, email addresses or database IDs as security authority. If pairwise subjects are later introduced, the pairwise derivation policy is Authentication-owned and tenant-bound.

### OIDC/OAuth security baseline

The first supported interactive flow is Authorization Code with PKCE. PKCE `S256` is mandatory for public clients and remains the default expectation for interactive clients generally. Exact redirect-URI matching is required. Implicit grant and Resource Owner Password Credentials grant are not supported.

Client registrations are explicit governed state. Public clients do not receive a client secret. Confidential-client credentials, when added, must remain behind Credential/secret-management boundaries rather than being exposed through ordinary client APIs.

Authorization codes, refresh tokens, browser session identifiers and other bearer-equivalent artifacts are opaque/sensitive security material. They must be short-lived or revocable as appropriate, stored only in protected form where persistence is required, excluded from logs/audit/errors/URLs except where a standards-defined front-channel artifact is unavoidable, and never returned by administrative resource APIs.

OIDC discovery and public verification keys may be public. Signing private material remains non-exportable through the Platform signing boundary. Key rotation must allow a bounded verification overlap without exposing private material.

### Claims and authorization separation

ID/access-token claims are curated and data-minimized. Authentication may emit protocol claims such as issuer, subject, audience, tenant context, authentication time/assurance and explicitly governed profile claims, but it must not serialize arbitrary Identity attributes or provider-native claims by default.

External IdP roles/groups/scopes and Wyrmgate-issued OAuth scopes are never converted directly into:

- `AdministrativePermission`;
- `AdministrativeRole` / `AdministrativeGrant`;
- `AccessAssignment`;
- Governance approval/review authority.

The existing Administration operation-time evaluator remains final for Wyrmgate control-plane commands. A Wyrmgate-issued token can establish an authenticated subject, but server-side governed actor binding/current authority still decides what that actor may do.

### First-party login and assurance

First-party authentication resolves a tenant-scoped local login binding to a current governed Principal/Identity and delegates authenticator verification to Credential. Successful password or other authenticator verification does not create administrative authority.

Authentication produces provider-neutral assurance semantics for downstream authorization. Strong assurance is based on explicit supported authenticator/session policy; it is never fabricated from an arbitrary claim string.

Identity/Principal suspension, credential compromise/revocation and explicit Authentication-session revocation must fail closed for new token/session issuance. Long-lived sessions and refresh grants must re-check the security conditions defined by Authentication policy rather than treating an old successful login as permanent authority.

### Control-plane authentication modes

ADR-0011's externally issued JWT resource-server mode remains a supported mode, but it is no longer the only architectural authentication target. Wyrmgate may additionally accept its own first-party issued tokens/sessions when Authentication is enabled.

Regardless of transport mode, the control plane still resolves the authenticated subject to a server-side governed actor and invokes Administration's semantic authorization evaluator. Provider claims and OAuth scopes remain non-authoritative for IAM administration.

### Initial administrator bootstrap

There is still no permanent `admin/admin`, default superuser password, hidden root account or wildcard administrative role.

ADR-0011's burn-once external-subject bootstrap remains valid for federated deployments. A first-party standalone bootstrap may establish the initial tenant administrator and first local authenticator/login binding only through an explicit operator-only one-shot path with operator-supplied secret material. It must preserve the same burn-once marker principle, use an existing administratively eligible governed Identity/Principal, avoid HTTP self-bootstrap, avoid persisting raw secret material in ordinary IAM tables, and create only explicit finite Administration permissions.

Any expansion of the initial administrator permission set needed to configure Authentication clients/login policy must be explicit and finite; `GLOBAL` remains a scope, not a wildcard permission.

### Administrative contracts for Authentication

Authentication administration uses semantic resources and operations with the same platform contract discipline as other authoritative capabilities:

- default-deny Administration permission checks;
- tenant isolation;
- optimistic revision/ETag semantics for mutable resources;
- causal idempotency where duplicate mutations are harmful;
- deterministic cursor pagination for large collections;
- write-only/redacted handling of secret references;
- immutable/data-minimized Audit evidence outside the authoritative transaction.

The initial finite administrative permission namespace is Authentication-owned in meaning and registered in Administration's finite permission registry. It must distinguish read-only visibility from mutation authority rather than introducing a wildcard `authentication:*` permission.

### Browser/session boundary

The Wyrmgate console should prefer a same-origin secure browser session/BFF-style boundary when using first-party authentication. Bearer/refresh tokens and session secrets must not be persisted in browser `localStorage` or `sessionStorage`. Cookies carrying session authority are `Secure`, `HttpOnly`, use an appropriate `SameSite` policy, and are protected against CSRF on state-changing browser requests.

### Formal specification checkpoint

The v0.8 BRD/FRD/SRS/DDD/SAD/Data/Security/Integration/RTM package remains the controlled document set but is amended by this ADR where it describes Wyrmgate as IGA-only, treats Authentication only as supporting infrastructure, or constrains control-plane authentication to an external IdP. The next meaningful controlled formal revision must fold this IAM/IdP/SSO scope and its traceability into the package.

## Supersedes / amends

- **ADR-0001** is amended only in its canonical capability list: `Authentication` is now a canonical logical capability. Its framework-neutrality, ownership and modular-monolith rules remain unchanged.
- **ADR-0011** is amended only where external JWT bearer authentication was the sole architectural target. External bearer authentication, exact issuer+subject binding, default-deny Administration authorization and the existing burn-once external bootstrap remain valid supported behavior.

## Consequences

- Wyrmgate can be deployed as both the governance system and the enterprise IdP/SSO authority.
- Standalone installations no longer require a separate third-party IdP merely to authenticate Wyrmgate users once the first-party runtime slice is enabled.
- Authentication becomes a real bounded capability with its own state rather than a framework helper hidden under Platform.
- Credential and Administration retain narrow ownership; neither becomes a generic authentication/authorization dumping ground.
- Existing external-IdP deployments remain viable and provider-neutral.
- OAuth/OIDC protocol authorization cannot silently redefine IAM business access or administrative authorization.
- The formal v0.8 package carries controlled update debt until the next reviewed revision.

## Rejected alternatives

- **Keep Wyrmgate IGA-only and require an external IdP:** contradicts intended product scope and prevents standalone IAM/SSO operation.
- **Put IdP state in Credential:** credentials are authentication instruments, not owner of sessions, relying parties, grants or claim policy.
- **Put IdP state in Administration:** conflates authentication with authority to administer IAM and creates a second authorization model.
- **Reuse Catalog Application as the OIDC client aggregate:** business application governance and protocol registration evolve differently; they may reference each other without sharing ownership.
- **Use OAuth scopes/groups as Wyrmgate administrator roles:** bypasses Administration's scoped/default-deny authority model.
- **Ship a permanent default superadmin credential:** creates an enduring bypass and violates the burn-once, explicit-authority model.
- **Claim broad SSO protocol support immediately:** protocol support is security-sensitive product scope and must be implemented/verified explicitly.