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

## Implemented backend contract

V15 implements this decision without introducing a new public Catalog resource:

- `CatalogAccessTargetAvailabilityQuery` returns an optional decision for a known Role or Entitlement. Missing targets remain a separate not-found condition rather than a fourth availability state.
- Entitlement decisions include owning Application and referenced ApplicationTarget lifecycle. APPLICATION Role decisions include owning Application lifecycle. BUSINESS Role terminality remains independent of Applications, while invalid current expansion is temporary unavailability.
- AccessAssignment creation requires `ASSIGNABLE` before authority is created.
- EffectiveAccess reads and semantic queries revalidate every supporting AccessAssignment against current Catalog availability, so stale projection rows cannot preserve authority after retirement or during temporary Role invalidity.
- Catalog retirement starts/reuses `CatalogAvailabilityPropagation`, a Catalog-owned durable bounded process. Application/ApplicationTarget retirement is never expanded into an unbounded synchronous transaction.
- RoleVersion activation uses the same bounded Catalog propagation mechanics for the changed Role and active BUSINESS-role dependents; activation never enumerates an unbounded parent set in the authoritative transaction.
- Propagation emits minimized `catalog.access-target-availability-changed` triggers for affected Role/Entitlement targets and bounded `catalog.role-expansion-changed` triggers for affected Role expansions.
- EffectiveAccess support revalidation checks the current Role expansion for role-derived support, so a superseded RoleVersion cannot keep an entitlement effective while projection repair is pending.
- Access consumes only the target-availability trigger, re-reads the current decision and creates/reuses `AccessTargetReduction` only when the exact material cause still matches a terminal decision.
- `AccessTargetReduction` pages exact non-terminal assignments by deterministic `(createdAt,id)` continuation and uses the ordinary Access termination command for CANCELLED/EXPIRED/REVOKED semantics.

The two durable process tables are `catalog.access_target_availability_propagation` and `access.access_target_reduction`. They are process state owned by their respective capabilities; Platform outbox/scheduler mechanics remain delivery infrastructure only.

## Interface boundary

The architecture requires a typed current-state Catalog availability query and minimized internal change triggers/facts. No public target-availability CRUD or status-mutation endpoint is introduced. Exact physical names may evolve without changing the ownership model.
