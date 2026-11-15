# ADR-0059: Local authoritative Access admission serialization

Status: Accepted

## Decision

For the modular monolith, serialize privilege-increasing Access intent for the same tenant and Identity using an Access-owned local transaction fence. This is not a distributed lock, persisted reservation, or new authority. Acquire the fence before current mandatory Governance evaluation, hold it until Access intent commits, and release at transaction completion. No provider call, human approval wait, or external operation may occur inside this transaction. A later service extraction must replace this local coordination with an explicit durable protocol.

Request application re-reads current RequestItem state, recovers causal replay before evaluation, evaluates current Governance context, and applies only unchanged positive material context in the fenced transaction. APPLIED is recorded separately and recovers a committed causal result. Lifecycle reconciliation takes the same fence for each increase, re-reads its policy and Identity epoch/predicate, evaluates the mandatory guard, and creates within scope. Manual grant and resume use the fence and current SoD rules; administrative permission does not waive a mandatory rule. A REQUIRE_APPROVAL conflict requires the AccessRequest path, not manual override. Missing mandatory policy/dependency fails closed. Authoritative reductions do not acquire the fence or require Governance. Current request defaults still govern requests only; lifecycle/manual use the typed SoD rules.

## Authoritative conflict context

The Access semantic query examines current-epoch ACTIVE and SCHEDULED assignment intent whose half-open validity interval overlaps the requested future/current interval. It validates current Catalog availability, Role expansion and SPECIFIC Principal compatibility. Expired, revoked, suspended, terminal targets and stale epochs do not contribute. Temporary unavailability and budget exhaustion fail closed. The query is bounded to 200 rows per page, 1000 candidate assignments, 10000 expansion paths and 2000 counterpart entitlement IDs; no EffectiveAccess projection may serve as a negative admission proof. Request-internal pairs are considered only if their validity windows overlap. Exceptions retain their existing decision-time validity semantics; subsequent expiry and observed conflicts are evaluated by findings work, not perpetual reservations.

## Scope and consequences

V26 adds the Access-owned tenant/Identity fence row and supporting index. It has no cross-capability Identity foreign key and no domain state. Transaction-scoped execution permits are not grants. This eliminates write skew between governed request, lifecycle and manual admissions; it does not serialize unrelated Identity/Catalog/Policy edits. Their existing epoch/current-target checks remain mandatory; lifecycle approval material context requires the separate GOV-V3-04 revision. No global/provider SoD guarantee is asserted. A configured active Governance policy is required before manual privilege increases. The public API returns stable fail-closed errors; existing non-Governance test fixtures inject explicit test-only guards.
