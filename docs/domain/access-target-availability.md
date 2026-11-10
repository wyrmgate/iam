# Catalog Access-Target Availability and Access Reduction

## Purpose

This is the living implementation-facing contract for ADR-0052. It separates Catalog target availability authority from Access assignment authority and defines the fail-safe response to terminal target retirement.

## Ownership

Catalog owns whether a Role or Entitlement is currently assignable/available as a governed target.

Access owns `AccessAssignment`, `EffectiveAccess`, desired technical-state projections and `AccessTargetReduction`.

Catalog never edits AccessAssignment. Access never infers Catalog state from persistence or provider observation.

## Availability decision

Catalog exposes a typed semantic decision:

```text
ASSIGNABLE
TEMPORARILY_UNAVAILABLE
TERMINALLY_UNAVAILABLE
```

The result includes stable target identity and the material Catalog cause resource/revision needed for explanation and causal processing.

### Terminal conditions

- Entitlement: terminal when the Entitlement, its owning Application, or its referenced ApplicationTarget is retired.
- APPLICATION Role: terminal when the Role itself or its owning Application is retired.
- BUSINESS Role: terminal only when that Role itself is retired.

### Temporary conditions

A recoverable missing/invalid current Role expansion or version transition is `TEMPORARILY_UNAVAILABLE`. It can suppress current role-derived EffectiveAccess while invalid but never authorizes destructive assignment termination.

## New access

Creating new AccessAssignment requires current `ASSIGNABLE` target state. Missing/ambiguous mandatory Catalog context fails closed for privilege increase.

## Semantic suppression

A `TERMINALLY_UNAVAILABLE` target stops contributing semantic EffectiveAccess immediately. Materialized projections and provider residue may lag, but they cannot preserve authority.

## AccessTargetReduction

Access owns a durable bounded process keyed causally by Tenant + target + Catalog cause resource/revision.

Each page:

1. re-reads current Catalog availability;
2. selects bounded current non-terminal assignments affected by the terminal target;
3. revalidates current assignment state/revision;
4. applies ordinary terminal semantics:
   - future SCHEDULED -> CANCELLED;
   - already-ended -> EXPIRED;
   - otherwise -> REVOKED;
5. advances deterministic continuation/checkpoint state.

Duplicate/replayed/out-of-order triggers are safe because current Catalog and Access state are re-read. If the target is no longer terminal under the relevant current context, stale reduction work stops without inventing authority.

A later successor target/version does not resurrect terminated assignments. New business intent creates new authority.

## Provider convergence

Access projection/desired-state absence drives Integration remove/disable work. Provider execution happens outside authoritative Access/Catalog transactions.

Provider cleanup failure remains fulfillment/observation drift and may produce Governance findings. It cannot restore Access authority.

## Interface boundary

The architecture requires a typed current-state Catalog availability query and a minimized change trigger/fact. Exact internal schema names, public endpoints and physical persistence are implementation contracts and may evolve without changing this ownership model.
