# ADR-0038: Provenance-fenced source suspension restoration

Status: Accepted

## Context

ADR-0023 permits explicit source lifecycle policy to move an ACTIVE Identity to SUSPENDED, but intentionally prevents a later mapped ACTIVE observation from performing SUSPENDED -> ACTIVE. That protects IAM/operator suspension from being silently undone by source observation.

ADR-0027 later introduced a narrower restoration precedent for INACTIVE: automatic restoration is allowed only when immutable evidence proves that Wyrmgate's own trusted COMPLETE absence inference caused the exact current INACTIVE revision and the same SourceRecord -> IdentityLink relationship is still current.

The remaining OD-003 lifecycle gap is source-caused suspension recovery. Without a durable provenance fence, either every mapped ACTIVE value remains unable to recover a temporary source suspension, or source observation gains authority to undo unrelated IAM suspension. The architecture needs the same distinction between observation and provenance already used for absence restoration.

## Decision

### Only source-caused suspension is automatically restorable

When the current active SourceLifecyclePolicyVersion maps the current positive SourceRecord value to SUSPENDED and ordinary Identity lifecycle processing actually performs ACTIVE -> SUSPENDED, Identity records immutable `SourceSuspensionTransitionEvidence`.

The evidence binds:

- SourceSystem;
- SourceRecord;
- the current accepted IdentityLink;
- Identity;
- the SourceLifecyclePolicyVersion that caused suspension;
- pre-transition Identity revision;
- resulting SUSPENDED Identity revision;
- transition time.

The evidence is historical proof of cause. It is not reusable lifecycle authority.

### Exact current revision and relationship are the restoration fence

A current mapped ACTIVE observation may perform SUSPENDED -> ACTIVE only when all of the following are true:

- the SourceRecord still has the same current ACCEPTED IdentityLink captured by the evidence;
- that link still points to the same Identity;
- the Identity is currently SUSPENDED;
- the Identity's current revision exactly equals the evidence post-transition revision; and
- the current active SourceLifecyclePolicyVersion independently maps the current observed value to ACTIVE.

Any intervening Identity mutation advances revision and invalidates automatic restoration. Replacing the accepted link invalidates restoration because the current link ID changes. A different SourceRecord cannot use another source record's suspension evidence.

A superseded lifecycle policy may remain referenced by historical evidence, but only the current active policy decides whether today's source value requests ACTIVE.

### IAM/operator suspension remains protected

A SUSPENDED Identity with no exact current `SourceSuspensionTransitionEvidence` is never automatically restored by source processing.

Therefore:

- an operator/API suspension cannot be undone by a raw positive source value;
- a suspension caused by another SourceRecord cannot be undone by this SourceRecord;
- cross-source precedence or consensus is not inferred;
- arbitrary lifecycle provenance is not introduced.

Explicit-source INACTIVE remains protected except for ADR-0027 trusted-absence restoration. DECOMMISSIONED remains terminal.

### Ordinary lifecycle mutation and Access semantics are reused

Restoration invokes the existing Identity lifecycle command for SUSPENDED -> ACTIVE. Identity remains the only owner of authoritative lifecycle.

Identity does not mutate Access. The existing eligibility fact and Access lifecycle reconciliation path handle current policy-owned access after reactivation.

AccessAssignments already terminated by the earlier eligibility-loss reduction are not resurrected. Any newly applicable lifecycle/birthright assignment is recreated only from current Access-owned policy and Governance checks.

### Replay and ordering

Processing remains current-state based and at-least-once safe.

- duplicate SUSPENDED observations after suspension are no-ops and do not manufacture a new Identity revision;
- successful restoration advances Identity revision so replay can no longer match the suspension evidence;
- a later genuine source-caused ACTIVE -> SUSPENDED transition records fresh evidence for its new exact revision;
- stale observation facts re-read the current SourceRecord and current active policy rather than replaying historical source payload as authority.

Evidence insertion is part of the same Identity-owned transaction as the source-policy lifecycle transition. A failure to persist evidence rolls back the source-caused suspension rather than leaving a restorable transition without provenance.

## Consequences

- temporary source-caused suspension can recover automatically when that same source reports ACTIVE again;
- IAM/operator suspension remains fail-closed against source restoration;
- relink, cross-source activity and intervening Identity changes fence stale restoration;
- capability ownership and ACTIVE-only access eligibility remain unchanged;
- no generic lifecycle-provenance framework, rules engine or cross-source precedence model is introduced.

## Deferred

- cross-source precedence/consensus for lifecycle restoration;
- restoration of operator/IAM suspension by source policy;
- generic provenance for every Identity lifecycle cause;
- explicit-source INACTIVE -> ACTIVE beyond ADR-0027 trusted-absence evidence;
- resurrection of historical non-policy AccessAssignments;
- arbitrary rules/BPM.
