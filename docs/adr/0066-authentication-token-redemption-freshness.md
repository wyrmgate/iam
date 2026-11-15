# ADR-0066: Authentication v3 authorization-code redemption freshness

Status: Accepted

## Context

ADR-0042 defines Authentication/IdP/SSO as a composed service, not another Layer-4 capability. Credential v3 (ADR-0063–0065) protects exact local authenticator proof. Issue #517 reconciles the supplied Authentication diagrams. The existing code redemption validates client revision, redirect and PKCE but does not recheck the original session or governed application access. Browser session touch also permits backward time updates.

## Decision

- Before issuing any tokens on authorization-code exchange, consume the code once, validate its original client registration/revision, Application, redirect, scope and S256 PKCE binding, then resolve the exact original browser session by trusted Tenant and stable session ID. This internal lookup is not a public authentication endpoint or a substitute for possession proof; it is reached only after successful code possession and binding validation.
- Revalidate current Identity/Principal eligibility, exact establishing Credential ID/revision and temporal effectiveness, session idle/absolute expiry and revocation. The stored code's Principal, Identity and authentication time must match the session. If governed application access is required, re-evaluate current Access eligibility before signing. Missing/stale context or denial is invalid_grant; unavailable mandatory evaluation is temporarily_unavailable. No token on either outcome. A consumed code is never restored, including on transient failure. The client starts a new authorization flow.
- Redemption is not browser activity and does not refresh session idle expiry. Browser touch is monotonic, bounded by absolute expiry, and rejects expired/revoked rows at its update predicate. Delayed requests cannot regress timestamps. Current checks are operation-time checks, not a distributed lock on all domain authority; changes after the check do not retroactively invalidate issued JWTs. Existing stateless tokens retain their bounded lifetime absent a separate online revocation contract.
- Keep all existing ownership boundaries. No new business aggregate, provider calls, or AccessAssignment mutation. Subsequent slices decide subject eligibility epoch, Tenant lifecycle gate, interactive login, runtime retention, federation proof and evidence; they are not implied by this amendment.

## Consequences

The first-party baseline rejects stale codes after session logout, Credential invalidation or lost governed application access. Existing public endpoint shapes remain unchanged. No schema change is required for this slice.
