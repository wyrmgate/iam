# ADR-0063: Credential v3 lifecycle and authenticator fencing

Status: Accepted

## Context

Issue #513 reconciles the supplied Credential v3 diagrams with ADR-0006, ADR-0020 and ADR-0042. Preserve Credential and CredentialRotation as separate tenant-owned aggregates, with Identity-owned Principal references. The five existing kinds describe metadata, not universal execution support.

## Decision

- Credential effectiveness requires ACTIVE and the half-open temporal window [validFrom, validUntil), with absent bounds unbounded. Due SCHEDULED is not effective until activation is committed; expiry cannot extend authority. SCHEDULED may be revoked or compromised without activation. COMPROMISED is permanently unsafe and may be explicitly revoked retaining compromisedAt; neither state can reactivate. These clarify ADR-0020's lifecycle graph.
- Routine rotation admission is serialized against the old Credential lifecycle using a same-capability row lock inside the authoritative transaction; the one-open-rotation constraint remains. A replacement must have the same tenant, Principal and kind, a different ID and distinct opaque reference. Reference inequality is necessary, not proof of distinct underlying secret material. Concrete execution must establish independent material and stable/version-pinned reference; no mutable alias may bypass credential revision/session fencing.
- Verification and completion must check the current replacement under the Credential transaction. Terminal rotations remain immutable, including MANUAL_REQUIRED; future recovery is a separately authorized forward operation, not a status reset. Existing internal transition methods are not proof of provider fulfillment. No general rotation runner, overlap policy, CredentialBinding or external provider execution is introduced until concrete execution contracts exist. First-party password is the preferred next concrete execution path; dedicated enrollment/change input is separate from ordinary resource API. Provider cutover and cleanup evidence and bounded overlap must be defined for each supported execution path before it is enabled.
- First-party password verification passes trusted tenant and exact Credential ID to the private verifier adapter. The local verifier must match both ownership fields, not just opaque reference. Re-read current Credential revision/lifecycle before returning proof; proof is only valid within the trusted tenant context, and session establishment/revalidation still checks exact Credential, Principal and Identity eligibility. No raw password leaves the secured input/verifier boundary.
- Keep the isolated one-way local password verifier store for first-party authentication. External create/distribute/revoke and observation belong to Integration, not the authoritative Credential transaction. Catalog owns relying-party registration, not CredentialKind; Principal-bound OAuth client secret is not a confidential SSO client authenticator. Credential lifecycle does not mutate AccessAssignment. Administration authorizes management; Audit owns minimized evidence.

## Consequences

No new aggregate, generic binding, or secret vault. Existing historical records are not rewritten. This slice hardens current metadata, local verification and rotation admission/transition semantics; it does not claim end-to-end provider rotation. Further execution design must specify operator/subject authority, independent replacement material, bounded overlap, evidence, causal retries and cleanup before implementation.
