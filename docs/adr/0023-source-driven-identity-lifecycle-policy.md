# ADR-0023: Source-driven Identity lifecycle policy

Status: Accepted

## Context

ADR-0021 defines authoritative Identity lifecycle, ACTIVE-only access eligibility and durable
Access-owned reduction after eligibility loss. ADR-0022 defines positive source-driven correlation,
optional PENDING Identity construction and canonical attribute materialization while explicitly
deferring source lifecycle policy and destructive absence inference.

The v0.4 formal Joiner/Mover/Leaver flow requires lifecycle policy after correlation/canonical
resolution, and distinguishes an explicit authoritative termination fact from inferred absence.
The missing decision is how a current linked SourceRecord may drive Identity lifecycle without
making source observation itself authoritative, bypassing Identity transition invariants, or
silently restoring privilege from stale/multi-source state.

## Decision

### Lifecycle policy is Identity-owned, explicit and versioned

Each SourceSystem may have at most one ACTIVE SourceLifecyclePolicyVersion. Activation creates an
immutable version and supersedes the previous version.

The first bounded policy contains:

- one bounded source path using the existing `$.field[.nested]` extraction convention; and
- one or more exact non-blank text-value rules mapping to `ACTIVE`, `SUSPENDED`, `INACTIVE`
  or `DECOMMISSIONED`.

`PENDING` is not a policy target. Missing, non-text, blank or unmapped source values are a no-op.
No generic expression language, arbitrary JSON rule payload or provider script becomes canonical
lifecycle semantics.

### Application requires current accepted correlation

A lifecycle rule may act only when the current SourceRecord has a current ACCEPTED IdentityLink.
Processing re-reads the current SourceRecord, current accepted link, current ACTIVE lifecycle policy
and current Identity before mutation.

The source value is therefore an input to an explicit governed policy. It is not itself Identity
authority.

### Privilege increase is intentionally narrow

The first implementation permits source-driven `PENDING -> ACTIVE` only.

A mapped ACTIVE value is a no-op for `SUSPENDED` or `INACTIVE`. Source-driven privilege
restoration from either state requires a later decision about multi-source precedence,
reactivation evidence and access-restoration policy.

This means a raw source observation cannot silently restore access that IAM previously suspended
or deactivated.

### Explicit reductions reuse the authoritative Identity lifecycle

A mapped reduction may apply only through the existing legal Identity lifecycle transitions:

- `ACTIVE -> SUSPENDED`;
- `PENDING|ACTIVE|SUSPENDED -> INACTIVE`;
- any non-terminal state -> `DECOMMISSIONED`.

`DECOMMISSIONED` remains terminal.

Source processing invokes the ordinary Identity lifecycle command. It never mutates Access state.
When an ACTIVE Identity becomes access-ineligible, ADR-0021 therefore emits the existing minimized
eligibility fact and Access owns bounded durable assignment reduction.

### Explicit source fact is not absence inference

A mapped lifecycle field carried on a positively observed SourceRecord is an explicit source fact.
It may be applied independently of whether the surrounding import later completes as COMPLETE or
PARTIAL.

Missing records are different. This ADR does not infer lifecycle from a SourceRecord that was not
seen. COMPLETE-gated destructive absence, source health/coverage requirements and mass-Leaver
safeguards remain a separate later stage.

### Replay, ordering and causality

Processing remains at-least-once and current-state based.

- stale source-observation facts do not replay old payload as authority;
- duplicate processing of an already reached lifecycle state is a no-op;
- policy replacement is versioned and current processing uses only the active version;
- terminal DECOMMISSIONED cannot be restored;
- lifecycle mutation preserves the source chain correlationId and uses the source/link fact as
  immediate causationId;
- lifecycle mutation time is never moved backwards relative to current Identity state.

No additional long-running process aggregate is required for one bounded positive SourceRecord.
The existing durable source processor and outbox are sufficient.

## Consequences

- Joiner construction can now progress from policy-created PENDING to ACTIVE under explicit source
  lifecycle authority.
- Explicit source suspension/termination reuses existing lifecycle and Access reduction semantics.
- Privilege restoration remains conservative and cannot be caused by a stale mapped ACTIVE value.
- Partial imports remain safe: explicit facts can reduce authority, while missing records cannot.
- Lifecycle policy is typed/versioned instead of embedded in connector/provider configuration.

## Deferred

- COMPLETE-gated destructive absence and mass-Leaver handling;
- source health/coverage policy for absence;
- source-driven SUSPENDED/INACTIVE -> ACTIVE reactivation;
- baseline/birthright Joiner access;
- Mover access-delta policy;
- manager/organization lifecycle policy;
- Identity merge/split;
- generic workflow/BPM infrastructure.
