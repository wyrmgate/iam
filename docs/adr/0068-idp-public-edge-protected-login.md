# ADR-0068: Same-origin public IdP edge and protected login input

Status: Accepted

## Context

The supplied Authentication v3 design requires discovery, JWKS, authorize and token endpoints reachable at the public issuer, not only the same-origin Console API. Current shared DEV Pages routing only forwards /api/*. Local login accepts a presented password but session establishment lacks the pre-authentication CSRF proof used for authenticated mutations.

## Decision

- Use one public HTTPS issuer origin for the current standalone Console/IdP deployment. The current implemented issuer is an origin (no non-root path); exact public discovery, JWKS, authorize and token endpoints are proxied to the same reviewed server as /api/*. Static Console paths stay static. Caddy reference and local dev proxy use the same route contract. No browser-supplied backend origin or automatic upstream redirect following; no caching of protocol or secret-bearing responses. This is an implementation/edge choice, not a new capability.
- GET /api/auth/login-route returns existing non-secret server-owned Console route metadata and issues a high-entropy Secure host-only SameSite=Strict pre-authentication CSRF cookie. POST /api/auth/login requires the matching header/cookie, JSON input and bounded client/target/native key/password fields before credential verification. Missing/invalid proof produces 403, invalid credentials remain non-enumerating. The same-origin Console already echoes the CSRF cookie through its API client. Use the exact same CSRF codec with a canonical 256-bit base64url format; no raw token logging.
- Keep existing login route selectors for this compatibility slice; tenant derives only from registered client. Explicit authentication-target realm selection and interactive authorization continuation are addressed separately, not assumed from this edge change.
- Keep dedicated /api/auth/password/change from ADR-0064. Add the first-party Console self-service form under the session experience; no tenant/Principal/credential selector, no persistent secret storage, no auto-retry, clear input on completion, require fresh login after success. Do not show this control for externally authenticated sessions.
- No external federation provider, consent engine, refresh token or new business authority. Deployment acceptance still requires actual issuer configuration, public routing, secure cookies and live protocol checks.

## Consequences

The public issuer routing contract changes from API-only to a precise API/protocol allowlist. Existing non-browser callers of local login must obtain and return pre-authentication CSRF proof; external bearer and standard authorization-code/token clients are unaffected. Browser session and code authority checks remain ADR-0066/0067.
