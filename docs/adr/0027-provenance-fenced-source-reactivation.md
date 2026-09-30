# ADR-0027: Provenance-fenced source reactivation after trusted absence inference

Status: Accepted

## Context

ADR-0023 permits source-driven PENDING -> ACTIVE but deliberately prevents a mapped ACTIVE value
from restoring SUSPENDED or INACTIVE Identity state. ADR-0024 later introduced trusted COMPLETE
absence inference and explicitly made its INACTIVE transition reversible, but the first persistence
slice records process counts rather than per-Identity transition provenance.

Allowing every current ACTIVE source value to perform INACTIVE -> ACTIVE would let source
observation undo an unrelated operator or other authoritative deactivation. Conversely, never
restoring absence-inferred INACTIVE state leaves a returning positively observed subject stranded.

## Decision

### Restoration is limited to Wyrmgate-owned inferred absence

Identity persists immutable SourceAbsenceTransitionEvidence whenever SourceAbsenceInference
actually changes an Identity to INACTIVE.

Evidence binds the SourceSystem, SourceRecord, accepted IdentityLink, import run, inference process,
Identity, pre-transition Identity revision, resulting Identity revision and transition time.

### Current Identity revision is the restoration fence

A positive lifecycle observation mapped to ACTIVE may perform INACTIVE -> ACTIVE only when all of
the following are true:

- the SourceRecord still has the same current ACCEPTED IdentityLink captured by the evidence;
- the linked Identity is currently INACTIVE;
- its current revision exactly equals the evidence post-transition revision; and
- the evidence belongs to that same SourceRecord/Identity relationship.

Any intervening Identity mutation advances revision and therefore invalidates automatic restoration.
Link replacement also invalidates restoration because the current link ID no longer matches.

The processor re-reads all current state inside the Identity transaction. Evidence is authority to
restore only the exact inferred transition it records; it is not generic lifecycle provenance.

### Suspension remains protected

SUSPENDED -> ACTIVE remains excluded from source automation. A source value cannot silently undo an
IAM suspension. DECOMMISSIONED remains terminal.

### Restoration uses ordinary lifecycle authority

The source lifecycle processor invokes the existing Identity lifecycle command for INACTIVE ->
ACTIVE. It does not mutate Access, Governance or provider state.

The resulting eligibility fact causes current Access-owned policy/projection reconciliation through
existing contracts. AccessAssignments previously terminated by ADR-0021 are not resurrected merely
because Identity becomes ACTIVE; any new policy-owned access must be recreated by current policy.

### Replay and ordering

Processing remains current-state based and at-least-once safe. After successful restoration the
Identity revision advances, so replay no longer matches the absence evidence and becomes a no-op.
Stale positive facts never replay historical payload as authority.

## Consequences

- returning subjects can recover from Wyrmgate's own trusted absence inference;
- manual/operator INACTIVE state cannot be overwritten by a raw positive source value;
- relationship replacement and intervening lifecycle changes fence stale restoration evidence;
- no generic lifecycle-provenance framework or second Access authority is introduced.

## Deferred

- source-driven SUSPENDED -> ACTIVE;
- cross-source precedence or consensus restoration;
- generic lifecycle provenance/evidence across every transition cause;
- resurrection of historical non-policy AccessAssignments;
- arbitrary rules/BPM.
