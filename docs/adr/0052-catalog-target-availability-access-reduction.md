# ADR-0052: Catalog target availability and Access-owned terminal-target reduction

Status: Accepted

## Context

Catalog owns the governable definition and lifecycle of Applications, ApplicationTargets, Entitlements and Roles. Access owns `AccessAssignment` authority and `EffectiveAccess`. The accepted architecture forbids Catalog from revoking Access authority directly, but an access target that becomes terminally unavailable must not continue to contribute semantic access merely because a background worker has not yet repaired assignments.

A second risk is over-destructive repair: a temporarily invalid Role expansion or transient Catalog inconsistency must not be treated as permanent retirement and used to destroy otherwise valid business intent.

The agreed Layer-4 Access/Catalog architecture resolves both problems through an explicit Catalog semantic availability decision and an Access-owned durable reduction process.

## Decision

### Catalog semantic availability

Catalog exposes a semantic access-target availability decision:

```text
ASSIGNABLE
TEMPORARILY_UNAVAILABLE
TERMINALLY_UNAVAILABLE
```

The decision is produced by Catalog from current authoritative state and includes stable target identity plus the material Catalog cause resource/revision required for explanation and causal processing.

The decision is semantic. Access must not infer it by reading Catalog tables, persistence flags or provider observations.

### Terminal versus temporary unavailability

For the agreed model:

- an `Entitlement` is terminally unavailable when the Entitlement itself is retired, its owning Application is retired, or its referenced ApplicationTarget is retired;
- an `APPLICATION` Role is terminally unavailable when the Role itself or its owning Application is retired;
- a `BUSINESS` Role is terminally unavailable only when the Role itself is retired;
- a Role that lacks a valid current expansion, has a recoverable version-transition problem, or otherwise cannot currently expand without a terminal owner/target condition is `TEMPORARILY_UNAVAILABLE`.

`TEMPORARILY_UNAVAILABLE` is deliberately non-destructive. It may suppress current role-derived EffectiveAccess while invalid, but it does not authorize assignment termination.

### New access creation

New `AccessAssignment` creation requires the target to be `ASSIGNABLE` at current-state validation time. Missing or unavailable mandatory Catalog context fails closed for privilege increase.

### Immediate semantic suppression

When a referenced target is `TERMINALLY_UNAVAILABLE`, affected access stops contributing to semantic `EffectiveAccess` immediately on current-state evaluation, even if materialized projections or non-terminal AccessAssignments still lag.

Catalog retirement therefore cannot leave business access semantically effective merely because Access repair or provider cleanup is delayed.

### AccessTargetReduction

Access owns durable `AccessTargetReduction` process state for bounded, resumable repair of non-terminal assignments affected by one terminal Catalog target/cause.

A reduction:

- is causally identified by Tenant + terminal target + material Catalog cause resource/revision;
- scans affected current AccessAssignments with deterministic bounded continuation;
- re-queries current Catalog availability before every mutation page;
- terminates only assignments whose current target is still terminally unavailable for that cause/semantic condition;
- uses ordinary Access terminal semantics: future scheduled access becomes `CANCELLED`, already-ended validity becomes `EXPIRED`, and otherwise the assignment becomes `REVOKED`;
- never resurrects a previously terminated assignment if Catalog later introduces a successor resource/version.

A stale trigger, duplicate delivery or out-of-order event is harmless because Access re-reads current Catalog authority and uses causal idempotency/revision checks.

### Ownership and transaction boundary

Catalog owns target lifecycle and availability meaning. Access alone mutates `AccessAssignment` and owns the reduction process. Governance does not mutate Access, and Integration/provider observation does not decide target retirement.

No distributed transaction spans Catalog and Access. Catalog commits its authoritative change plus a minimized semantic trigger/fact; Access re-reads current Catalog state and commits its own reduction separately.

Provider calls never execute inside Catalog or Access authoritative transactions. Resulting desired absence converges through Access projections and Integration provisioning/reconciliation. Provider removal failure remains visible fulfillment/observation drift and cannot restore Access authority.

### Interface boundary

The exact internal fact/command/query names are implementation-facing contracts. The architectural semantic is a typed current-state Catalog availability query plus minimized change trigger carrying stable causal context.

No new public API is required merely to implement this internal reduction path.

## Consequences

- Catalog retirement remains ownership-preserving and never becomes a hidden cross-capability cascade.
- Access remains the only owner of assignment authority while terminal Catalog unavailability takes effect fail-safe and immediately at semantic read time.
- Temporary Role expansion/version problems do not cause irreversible assignment destruction.
- Provider cleanup cannot lag business authority or rewrite it.
- Concrete persistence, API schemas and worker implementation remain later implementation work; this ADR defines the durable semantic boundary.
