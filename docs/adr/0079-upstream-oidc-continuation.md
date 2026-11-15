# ADR-0079: Explicit upstream OIDC authorization continuation

Status: Accepted

## Decision

An active Integration FederationTrust can be bound once to exactly one active same-Tenant Catalog SsoClientRegistration having an interactive authentication target. One active trust per registration. Binding is optimistic-revision fenced, requires dedicated Administration authority and mandatory Audit evidence, and increments trust revision. Retire/recreate instead of silently remapping. Trust/link ownership remains Integration. No browser-selected trust, tenant, target or external subject.

The first provider profile is OIDC Authorization Code + S256 PKCE, public client, pinned RSA RS256 verification key, exact issuer and single audience, nonce and fresh auth_time. Upstream endpoints must share the configured HTTPS issuer origin; deployment must explicitly enable federation and allowlist the endpoint origin. No dynamic discovery, client secret, automatic key rollover, SAML, or arbitrary claims mapping. Key replacement uses the explicit retire/recreate flow. A deployment egress policy must protect approved origins and DNS resolution.

The existing browser-bound local authorization interaction is consumed on explicit upstream selection. A distinct five-minute Platform interaction pins downstream registration/revision/target and trust/revision. Only hashes of upstream state/browser proof are stored; verifier and nonce derive from independent high-entropy browser proof and state with domain separation. The browser cookie is Secure, HttpOnly, SameSite=Lax for top-level GET callback. The previous session is revoked on upstream initiation (the Strict cookie may not return cross-site). Callback consumes its exact durable fence once before exchange, outside any authoritative transaction. No automatic retry after an uncertain exchange; restart authorization. Token response is bounded, redirects disabled, timeout enforced and errors minimized. Signature, issuer, audience, nonce, time and optional response issuer are validated. Only an exact current subject link and eligible same-target Principal yield an ADR-0078 anchor; the existing governed authorization flow issues the downstream code. Neither upstream tokens nor raw verifier/code are persisted or emitted as evidence.

Default disabled. Enable iam.idp.federation.enabled and configure iam.idp.federation.allowed-origins (comma-separated exact HTTPS origins). Configure the upstream callback at the public issuer plus /oauth2/federation/callback. Live provider/edge and negative end-to-end acceptance remain separately evidenced, not implied by code or configuration.

## Consequences

V39 adds the explicit registration reference and disposable interaction with bounded cleanup. No change to local PASSWORD sign-in or external bearer transport. The selected narrow profile is intentionally not general-purpose federation; other upstream profiles require accepted contracts.
