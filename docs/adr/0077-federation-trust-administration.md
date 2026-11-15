# ADR-0077: Explicit upstream OIDC trust administration and storage

Status: Accepted

## Decision

V37 implements Integration-owned FederationTrust and FederatedSubjectLink with same-tenant exact keys, immutable issuer/audience/provider configuration, explicit creation/terminal retirement with optimistic revisions, and unique active exact subject link within a trust. Trust retirement invalidates all linked authentication without rewriting historical links. Link remap requires retire/create. Identity semantic eligibility is checked at link creation and authentication; no cross-capability Principal FK or authority mutation. No observation mapping reuse.

Initial concrete profile is an explicitly configured upstream OIDC public client using authorization code/S256 PKCE and RS256 with a pinned public RSA verification key (SPKI, key ID). HTTPS issuer and endpoints must share origin, no endpoint query/userinfo/fragment, no dynamic discovery or secret. Key/endpoint rotation retires trust and creates a new trust/link set; no silent rollover. This deliberately narrow profile excludes confidential-client secrets, SAML, automatic JWKS discovery/rotation, external claim mapping and provider-driven account creation. Trusted deployment egress controls remain necessary. No provider is activated automatically.

Dedicated default-deny federation-trust read/create/retire and federated-subject-link read/create/retire permissions; standalone owner baseline deliberately adds these for fresh setup, existing grants are not silently rewritten. APIs use causal idempotency and optimistic revision, data-minimized Audit evidence; creation requires Audit evidence in the authoritative transaction, retirement commits before best-effort Audit so failure cannot block reduction. Actual upstream callback verification and federated session activation remain separate until durable interaction/replay and anchor persistence are implemented.

## Consequences

No provider credentials or private keys stored. Management can configure an explicitly trusted public key and exact account link; no public login is enabled by this slice. Live provider setup requires verified configuration and acceptance.
