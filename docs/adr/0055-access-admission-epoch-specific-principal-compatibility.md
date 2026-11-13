# ADR-0055: Access admission epoch fencing and SPECIFIC Principal compatibility

Status: Accepted

## Context

AccessAssignment is authoritative business access intent, but its semantic contribution depends on current Identity, Catalog and Principal meaning. Identity ineligibility already suppresses access and drives Access-owned reduction; Catalog availability is also re-read at semantic use time.

Two races remain material for v3. An AccessAssignment can validate an ACTIVE Identity and commit after that Identity has crossed an access-ineligible boundary. If asynchronous reduction has already passed, the late assignment can survive and later reappear after Identity reactivation. Separately, a SPECIFIC assignment can outlive a Principal reassignment or Role expansion change that makes the specified Principal incompatible with the governed Identity or target.

Neither problem is safely solved by distributed locking, cross-capability repository mutation, or provider fulfillment becoming authoritative.

## Decision

### Identity owns a monotonic access-eligibility epoch

Identity stores a positive accessEligibilityEpoch. It starts at 1 and increments exactly when Identity lifecycle crosses the access-eligibility boundary (ACTIVE versus non-ACTIVE). Ordinary display/profile revision changes do not advance this epoch.

The epoch is Identity authoritative metadata exposed through the narrow Access semantic query. It is not workflow state and does not replace Identity revision.

### Access captures the epoch at assignment admission

Every new AccessAssignment records the current eligible Identity epoch as identityAccessEpoch. Creation still fails closed unless the Identity is currently access-eligible.

An AccessAssignment can contribute semantic EffectiveAccess only while the Identity is currently access-eligible and its current epoch equals the assignment's captured epoch.

This is a fencing invariant, not a second Identity authority. Identity ineligibility still starts the ADR-0021 Access-owned durable reduction. The epoch makes a late/raced assignment harmless even when it commits after the reduction snapshot, and prevents an assignment from an older eligibility era from silently reviving after reactivation. New access after reactivation must be admitted in the new epoch. A suspended
assignment captured in an older epoch cannot be resumed into authority. Lifecycle-policy
reconciliation treats an otherwise matching policy assignment from an older epoch as stale intent:
it terminates that assignment with ordinary Access semantics and, if the current rule still
authorizes, admits a successor in the current epoch.

### SPECIFIC compatibility is current-state semantic eligibility

A SPECIFIC assignment never falls back to another Principal.

For a direct Entitlement assignment, the specified Principal must currently resolve to the same Identity and the Entitlement's ApplicationTarget.

For a Role assignment, the specified Principal must currently resolve to the same Identity and the Role's full current expansion must resolve to exactly one ApplicationTarget equal to the Principal's target.

If those compatibility rules are not currently satisfied, the assignment remains authoritative business/historical intent but contributes no semantic EffectiveAccess. The condition is non-destructive because Principal ownership corrections and Role expansion can change again. Current incompatibility must drive desired technical absence for any previously realized grant.

Principal lifecycle availability (for example a disabled but still correctly owned Principal) remains a technical-realization concern; it does not silently change the business assignment into a different Principal.

### Current-state revalidation is mandatory

EffectiveAccess reads and desired-state derivation re-read the current Identity epoch, Principal ownership/target, Catalog availability, and Role expansion. Materialized projection rows are never sufficient authority by themselves.

Principal-change and Role-expansion triggers are at-least-once and may repair projections asynchronously. Read-time semantic guards provide the immediate fail-safe boundary.

## Consequences

- Identity access reduction remains owner-preserving and asynchronous, while admission races cannot revive old privilege after reactivation.
- Display-name or unrelated Identity revision changes do not invalidate assignments.
- SPECIFIC assignments remain explainable and never guess another Principal.
- A Role becoming multi-target suppresses a SPECIFIC assignment without destroying its provenance.
- Cross-capability database locking or distributed transactions are not introduced.
- Existing assignments are backfilled to the current Identity epoch during the pre-release migration; later eligibility-boundary transitions fence them normally.
