# ADR-0071: Explicit federation trust, subject link and authentication anchor

Status: Accepted

## Context

ADR-0042 and Authentication v3 (#517) permit upstream OIDC/SAML but not a competing Identity authority. The existing adapter SPI returns an Administration ExternalAuthenticationSubject without a tenant/trust/interaction binding. That type is for external control-plane actor resolution, not upstream SSO. Replace this incomplete SPI before concrete Integration adapters exist.

## Decision

- Integration owns typed FederationTrust and FederatedSubjectLink configuration and lifecycle. A trust is tenant-bound, stable ID, exact protocol, provider implementation key, issuer and audience, revision and active eligibility. A link maps the exact stable subject within that trust to exactly one existing same-tenant Principal, with its own stable ID/revision/active eligibility. No username/email fallback, just-in-time creation, implicit observation mapping or reuse of Administration external actor binding. Concrete storage/administrative writes, uniqueness and immutable retire/remap history are Integration v3 work. An unavailable/missing/ambiguous current query denies.
- The provider adapter receives trusted server-selected verification context: Tenant, trust ID/revision, protocol, implementation key, exact issuer/audience, one-use browser-bound interaction ID and expiry. It verifies signature/keys, issuer/audience, nonce or InResponseTo, request/redirect binding, assertion time and replay protection using the concrete protocol. Only then may it return a minimized verified subject bound to that exact context, stable external subject, authentication time and proof expiry. Raw assertions/tokens, arbitrary claims and provider roles/groups are excluded. The provider must consume the durable callback/replay fence before returning proof; the value object alone is not cryptographic verification.
- Authentication composes current Integration trust/link reads and Tenancy/Identity eligibility. Match all context fields, require active exact trust revision and proof validity, exact active link, current eligible Principal/Identity and ACTIVE Tenant. Produce a typed federated authentication anchor containing only tenant/trust/link/subject revisions and IDs, authentication time and proof validity; no synthetic Credential and no external subject in the session anchor. Revalidation rejects changed/revoked trust or link, subject reassignment, changed Tenant/Identity/Principal revisions, and expiry. The current browser-session implementation remains local-only; no public callback or federated session issuance is enabled. Integration v3 must implement concrete protocol verification, durable interaction/replay fencing and trust/link persistence before a subsequent session-anchor persistence/issuance slice is enabled. Keep local password change local-only.
- Preserve the external bearer control-plane binding unchanged. The descriptor registry is not an enablement or authorization mechanism. No provider is implicitly active.
- Discovery advertises only openid as implemented claim scope and explicit claims_supported. Existing Catalog profile/email scope registration and compatibility acceptance are retained without claim release; neither scope is a promise of attribute delivery. Concrete governed claim release is deferred.

## Consequences

A typed, executable fail-closed contract and tests replace the old unbound adapter result. No new provider is enabled, no new migration is required for federation contract, and no claim of upstream login acceptance is made. Integration follow-up must implement durable trust/link configuration, provider-specific verification and callback/session activation before upstream login can be used.
