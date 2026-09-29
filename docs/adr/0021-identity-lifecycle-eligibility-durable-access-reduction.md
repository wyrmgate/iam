# ADR-0021: Identity lifecycle eligibility and durable Access reduction

Status: Accepted

## Context

Wyrmgate IAM v0.3 requires joiner/mover/leaver lifecycle governance. The first bounded
lifecycle slice addresses the security-critical Leaver path.

Identity owns the authoritative lifecycle of a governed subject. Access owns
AccessAssignment authority and the EffectiveAccess/desired-state projections derived from
it. A lifecycle reduction must therefore not be implemented by Identity directly mutating
Access tables, nor may asynchronous delivery delay extend effective authority.

The current Access runtime already has:

- authoritative AccessAssignment lifecycle and semantic termination;
- EffectiveAccess and desired-state projections;
- durable at-least-once outbox processing;
- provider revocation planning downstream of desired absence.

The missing boundary is the authoritative Identity lifecycle signal and the Access-owned
durable reduction process.

## Decision

### Access eligibility follows authoritative Identity lifecycle

The first lifecycle policy defines only `ACTIVE` Identity as access-eligible.

```text
ACTIVE          -> access eligible
PENDING         -> access ineligible
SUSPENDED       -> access ineligible
INACTIVE        -> access ineligible
DECOMMISSIONED  -> access ineligible
```

This is a semantic rule, not a projection state.

Identity exposes a semantic access-status query so foreign capabilities can distinguish:

- `NOT_FOUND`;
- `ACCESS_ELIGIBLE`;
- `ACCESS_INELIGIBLE`.

Access EffectiveAccess semantic reads re-check this Identity-owned status. Therefore a
completed authoritative lifecycle transition away from ACTIVE makes EffectiveAccess
semantically absent immediately, even before asynchronous AccessAssignment reduction has
materialized.

### Identity lifecycle mutation

Identity owns explicit lifecycle transition semantics and optimistic revision.

Initial allowed transitions are:

```text
PENDING -> ACTIVE | INACTIVE | DECOMMISSIONED
ACTIVE -> SUSPENDED | INACTIVE | DECOMMISSIONED
SUSPENDED -> ACTIVE | INACTIVE | DECOMMISSIONED
INACTIVE -> ACTIVE | DECOMMISSIONED
DECOMMISSIONED -> no further transition
```

A no-op transition is idempotent only when the caller supplies the current revision.
`DECOMMISSIONED` is terminal.

When a lifecycle transition changes access eligibility, Identity appends a data-minimized
internal fact atomically with the authoritative Identity update.

The fact carries only the new lifecycle/access-eligibility semantics and normal outbox
aggregate revision/correlation metadata. It is not itself Access authority.

### Durable Access reduction

Access consumes access-ineligible Identity lifecycle facts and owns a durable
`IdentityAccessReduction` process.

The process is causally unique by:

```text
tenant + identityId + sourceIdentityRevision
```

It records:

- stable operation ID;
- Identity ID;
- source Identity revision;
- lifecycle value that caused reduction;
- processing state;
- deterministic `createdAt + assignmentId` continuation;
- processed assignment count;
- optimistic revision/timestamps.

The worker pages authoritative AccessAssignments in bounded batches. For each current
non-terminal assignment Access invokes its existing semantic termination behavior:

- future SCHEDULED -> CANCELLED;
- validity already ended -> EXPIRED;
- otherwise non-terminal -> REVOKED;
- terminal -> no action.

Each termination continues to emit existing Access projection-input facts.

The worker commits a continuation checkpoint after each page and queues/retains retryable
work until the operation is complete. Duplicate/replayed lifecycle facts resolve the same
causal process.

### Reactivation and out-of-order facts

An Access reduction operation must re-read current Identity access status before each
processing page.

If the Identity is currently ACCESS_ELIGIBLE again, a stale reduction operation completes
without further terminating assignments. A historical lifecycle fact therefore cannot
revoke authority newly established after reactivation.

This does not restore assignments that were already authoritatively terminated. Any
reactivation access policy is a later Joiner/Mover concern.

### Downstream convergence

There is no distributed Identity + Access transaction.

```text
Identity lifecycle reduction
  -> immediate semantic EffectiveAccess absence
  -> durable AccessAssignment termination
  -> EffectiveAccess support removal
  -> desired-state absence
  -> Integration revoke/disable planning
  -> provider observation/reconciliation
```

Provider failure cannot restore Identity eligibility or AccessAssignment authority.

### Other capabilities

This first slice does not automatically mutate Principal or Credential lifecycle. Those
capabilities remain authoritative for their own state and may consume the lifecycle
boundary in later typed slices.

Governance policy evaluation availability is irrelevant to authoritative privilege
reduction. Leaver reduction must continue even if Governance evaluation is unavailable.

## Consequences

- A leaver cannot remain semantically effective solely because async processing is late.
- Identity and Access ownership remain cleanly separated.
- Large identities are reduced through bounded resumable Access work rather than one
  unbounded transaction.
- Existing projection/provisioning chains are reused rather than duplicated.
- Reactivation does not make stale lifecycle facts destructive.
- Joiner/Mover automation can later build on the same Identity lifecycle facts without
  redefining this reduction contract.

## Deferred

- baseline-access grant policy for Joiners;
- Mover policy-delta computation;
- Principal lifecycle reaction;
- Credential lifecycle reaction;
- public lifecycle API expansion;
- source-specific termination mapping;
- manager/organization lifecycle policies;
- Identity merge/split;
- new provider-residue finding types beyond current reconciliation/drift behavior.
