# ADR-0067: First-party session subject and Tenant revision fencing

Status: Accepted

## Context

ADR-0066 requires current session authority at code redemption. A session established before an Identity, Principal or Tenant suspension could otherwise become usable again if the subject reactivates before a request observes the suspension. Identity already exposes a typed authentication subject with current Principal/Identity revisions; Tenancy exposes an authoritative Tenant overview. The existing local session contains only the Credential revision.

## Decision

- First-party browser sessions require ACTIVE Tenant, ACTIVE correlated Principal and Identity, and effective exact PASSWORD Credential. Bind the trusted Tenant revision and the exact Principal and Identity revisions at establishment, in addition to Credential ID/revision. Recheck the same revisions and eligibility on every resolution, including code redemption. Revision mismatch is permanently invalid, even if current lifecycle is ACTIVE again. Revocation is opportunistic and never reactivates a session.
- Use existing revisions conservatively rather than add a new authentication epoch in this slice. Any subject revision change (including non-security profile changes) may require reauthentication; this is an explicit availability tradeoff. Tenancy revision is checked independently. A future narrower security epoch requires its own reviewed contract and migration.
- Tenancy's semantic read port supplies current Tenant metadata; Identity supplies current subject; Credential supplies exact authenticator validity. Authentication composes the reads without cross-capability persistence access. Trusted installation verification remains separate and must still work while Tenant is PROVISIONING; it is not browser session establishment. External bearer actor binding is not changed by this slice.
- V31 adds the three revision fields to Platform session state. Pre-upgrade sessions have no reliable establishing revisions: revoke them once, retain their existing historical rows with null revision fields, require positive revision fields for all usable sessions. Do not backfill fabricated historical proof. Previously issued JWTs retain their bounded expiry; unused codes tied to old sessions cannot be redeemed.
- No parallel User model, no session permission snapshot, no new provider execution. Local session proof is not a federated proof; upstream federation remains disabled until its own typed contract is implemented.

## Consequences

Upgrade requires users to sign in again. Existing local password rotation and current-state Credential checks remain intact. Identity/profile edits can invalidate a browser session; this is deliberate until a dedicated security-epoch contract is accepted.
