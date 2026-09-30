# ADR-0024: Trusted COMPLETE source absence inference

Status: Accepted

## Context

ADR-0021 defines ACTIVE-only Identity access eligibility and Access-owned durable reduction after
eligibility loss. ADR-0022 separates positive source processing from destructive absence inference,
and ADR-0023 adds explicit source lifecycle policy for positively observed source values.

The controlled v0.4 Integration specification adds a stricter requirement for absence: destructive
absence inference requires trustworthy COMPLETE coverage and usable connector/source health.
Completeness alone therefore cannot be treated as authority to create Leavers.

Source populations may also be large. Absence handling must be bounded, resumable and replay-safe,
and it must not let one unexpectedly small import automatically inactivate an unbounded number of
Identities.

## Decision

### Absence inference is separately governed

Each SourceSystem may have at most one active immutable SourceAbsencePolicyVersion.

The first policy defines only:

- whether destructive absence inference is enabled for that SourceSystem by the presence of the
  active policy; and
- a positive maximum number of inferred lifecycle transitions permitted automatically for one
  source import.

The initial inferred lifecycle action is fixed to `INACTIVE`. Automatic inferred
`DECOMMISSIONED` is not supported.

This keeps the first negative source policy typed and conservative rather than introducing a
generic expression/rules engine.

### COMPLETE is necessary but insufficient

Every completed SourceImportRun carries run-scoped absence trust:

- `UNTRUSTED` is the default and never authorizes absence inference;
- `TRUSTED` may be recorded only for a COMPLETE import and requires a non-blank reason/evidence
  summary confirming that the adapter/source health and coverage are suitable for destructive
  absence inference.

PARTIAL and UNKNOWN coverage can never be trusted for absence.

Existing ordinary import completion remains UNTRUSTED unless the caller explicitly completes the
run through the trusted-completion semantic command.

A run may be completed as TRUSTED only when no later import for the same SourceSystem has already
started. This prevents a stale or overlapping full-snapshot run from becoming destructive
authority.

### Absence is evaluated from a stable full-snapshot boundary

For a trusted COMPLETE import, only SourceRecords that existed before the run began and were not
positively observed in that run are absence candidates.

A candidate is re-read immediately before action. It is not absent if its current positive
observation is at or after the trusted run start.

The current accepted IdentityLink is also re-read. A link established or replaced after the
trusted run began is not acted on by that run. Absence evidence for an older source-to-Identity
relationship cannot terminate a newly correlated Identity.

### Long-running inference is Identity-owned and durable

A trusted COMPLETE import plus active SourceAbsencePolicyVersion starts at most one
SourceAbsenceInference process for that import.

The process stores:

- source/import/policy identity;
- the policy's fixed transition ceiling;
- a stable `firstObservedAt + SourceRecordId` checkpoint;
- processed-candidate and inferred-transition counts;
- process revision and terminal state.

Candidate work is bounded and resumable. Each candidate checkpoint and any resulting Identity
lifecycle transition commit atomically. This prevents crash/replay from losing transition-count
evidence and bypassing the mass-Leaver ceiling.

Before every page/candidate, current authority is revalidated:

- the import is still COMPLETE + TRUSTED;
- the originally selected SourceAbsencePolicyVersion is still active; and
- no newer import has started for the SourceSystem.

If any of these conditions no longer holds, the process becomes `SUPERSEDED` and performs no
further lifecycle transitions.

### Mass-Leaver ceiling fails safe

If another candidate requires an Identity lifecycle transition after the configured maximum has
already been reached, the process becomes `MANUAL_REQUIRED` before that transition occurs.

The first slice intentionally provides no automatic continuation after the ceiling is hit.
Operator review/resume is a later explicit control-plane feature.

### Inferred absence is reversible and reuses normal lifecycle authority

The first implementation maps valid inferred absence only to `INACTIVE`.

For a currently linked Identity:

- `PENDING|ACTIVE|SUSPENDED -> INACTIVE` may be applied;
- already `INACTIVE` is a no-op;
- terminal `DECOMMISSIONED` is a no-op.

The process calls the ordinary Identity lifecycle command. It never mutates Access state.
Consequently `ACTIVE -> INACTIVE` emits the ADR-0021 access-eligibility fact and Access performs
its existing bounded durable reduction.

### Delivery and causality

Import-completed and continuation work use the existing transactional outbox and at-least-once
delivery model.

Duplicate import-completed delivery creates at most one SourceAbsenceInference per import.
Duplicate/stale continuation work is revision-aware and no-ops when authoritative process state has
advanced.

Correlation and immediate causation are preserved through inferred lifecycle transitions.

## Consequences

- Existing COMPLETE imports remain non-destructive unless explicitly trusted.
- PARTIAL imports cannot create mass Leavers.
- Newer imports, new positive observations and post-snapshot correlation changes fence stale
  destructive work.
- Large source populations are processed with bounded checkpoints.
- An unexpectedly large inferred Leaver population stops at a policy-defined ceiling instead of
  continuing automatically.
- Access reduction continues through ADR-0021 rather than a second cross-capability path.

## Deferred

- operator review/resume/override API after `MANUAL_REQUIRED`;
- inferred automatic `DECOMMISSIONED`;
- source-driven reactivation from `SUSPENDED`/`INACTIVE`;
- birthright/baseline Joiner access;
- Mover access-delta policy;
- manager/organization lifecycle policy;
- Identity merge/split;
- generic workflow/BPM infrastructure.
