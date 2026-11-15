# ADR-0058: Current Governance admission and bounded SoD fencing

Status: Proposed — design review before GOV-V3-02/03 implementation.

## Context

ADR-0017 and ADR-0028 revalidate approval at the Governance decision boundary.
The authorized RequestItem is then delivered asynchronously to Access. Current
Access admission validates Identity/Catalog/Principal semantics but does not
bind the Governance evaluation to the admission epoch or current policy.
Current-access SoD reads are semantically guarded but materialized support may
lag authoritative assignment creation. Independent requests can both pass
before either assignment contributes to the projection.

This proposal extends the privilege-increase admission boundary. It does not
change Governance decision evidence into Access authority or require a generic
workflow engine. ADR-0055 remains the Identity epoch/Principal compatibility
contract; ADR-0056/0057 remain the technical demand and Role expansion contracts.

## Proposed decision

### 1. Current authorization is not an irrevocable grant

An AUTHORIZED RequestItem is eligible to attempt application, not a transferable
or indefinitely valid permission. For a new application attempt, Governance
must revalidate current active policy, current exact Role expansion, current
Identity eligibility epoch, current SPECIFIC Principal compatibility, current
SoD intent and exact effective exception coverage at a supplied decision time.
A completed approval is reusable only for the same material approval context,
not merely the same stage/member hash.

A typed immutable authorization evidence record binds:
- tenant, RequestItem stable ID/revision, beneficiary, target/constraint/window;
- Identity access-eligibility epoch (not unrelated display/profile revision);
- PolicyVersion, current relevant Role expansion/version fingerprint, and
  effective exception IDs/validity where used;
- current applicable conflict/rule context and required approval-plan fingerprint;
- completed ApprovalCase ID when required, evaluation ID/time and correlation.

An approval case's immutable subject context records the admission epoch and
material policy/target/approval requirement context. A material change requiring
a different approval creates a successor case; old decisions remain evidence.
An outage preserves previous completed evidence, but does not authorize until
the current evaluator succeeds. Rejection and explicit reductions remain
independent of evaluator availability.

Initial application after an Identity eligibility epoch change is cancelled
rather than silently admitted in the new era; a new request/approval is required.
Ended requested validity yields EXPIRED; terminal Catalog invalidity yields
CANCELLED; temporary unavailability retries. Current policy DENY prevents new
application. If current evaluation requires an unsatisfied approval, the item
returns to PENDING_APPROVAL with a successor case. If current evaluation is
unavailable, retain a durable evaluation/application retry with no new authority.

A previously committed Access result is looked up by RequestItem provenance
*before* new evaluation, so a crash between Access commit and Governance APPLIED
does not retroactively reinterpret or revoke a valid historical admission.
APPLIED still means Access accepted the assignment, never provider success.

### 2. Access owns a bounded admission reservation

Use a tenant + Identity scoped Access-owned admission fence/reservation with a
monotonic generation and one current lease/token. The reservation is technical
coordination of an Access business mutation, not permission, approval evidence,
a new assignment or a provider lock. Acquire/release through Access-owned
semantic operations. Keep the human approval stage outside the reservation.

The sequence is:
1. Access checks causal replay and reserves the current Identity admission
   generation with a bounded lease for a single candidate/operation.
2. Access exposes a bounded authoritative-intent conflict query, independent of
   EffectiveAccess projection freshness. It evaluates currently relevant
   assignment intent with current Identity epoch, target availability, Role
   expansion and Principal compatibility. Governance reads through this semantic
   port, evaluates current policy and writes its immutable evidence separately.
3. Access consumes the matching reservation token/generation and verifies the
   exact candidate/epoch, current material Governance context and expiration.
   The Access transaction commits the assignment (or resume), causal application
   result, generation advance and projection facts atomically.
4. Governance records APPLIED from the Access result in its own transaction.
   Outbox retries and expired leases never permit stale token consumption.

Every assignment privilege-increasing path participates in the fence, including
manual creation, requested creation, lifecycle creation and resume. Existing
manual authorization remains Administration-owned; a mandatory SoD control
contract applies without reusing ACCESS_REQUEST default decision. For manual
or resume REQUIRE_APPROVAL, the first implementation fails closed with an
explicit governed request/approval path rather than introducing implicit
approval. This extends the current manual/resume contract and requires API
compatibility and test migration.

A reduction never waits on Governance evaluation or a reservation. It may
advance the Access generation, invalidating a pending admission conservatively.
Do not hold locks over external work or human decisions. No distributed rollback
or cross-capability table mutation.

### 3. Concurrency and temporal semantics

The first implementation serializes *admissions* for one Identity; different
Identities proceed independently. A reservation is not sufficient if an
authoritative create/resume path can bypass it. Repository writes from ordinary
production application paths must all pass the owner command boundary.

SoD admission considers the proposed remaining validity interval
[max(now, validFrom), validUntil), with unbounded end when absent. Other current
ACTIVE/due SCHEDULED and future SCHEDULED intents whose remaining windows overlap
participate, independent of projection delay. SUSPENDED assignments do not
contribute until their separately fenced resume. Terminal, stale-epoch and
permanently unavailable authority does not contribute. Temporarily invalid or
unavailable expansion that prevents a complete relevant result makes evaluation
unavailable rather than returning partial authority. This is an explicit
extension of ADR-0017's decision-time current-access scope. Existing policy and
Role changes may create historical/current exposure; report it through findings,
not silent retroactive revocation. Catalog Role activation governance is a
separate typed extension; admission fencing alone is not a global continuous
SoD guarantee.

Use deterministic keyset pages with an explicit maximum synchronous candidate
budget (initial proposal: at most 1,000 relevant assignments and 10,000 expanded
paths per admission; 200 assignment rows per read, ADR-0057's per-Role limits
still apply). On overflow or missing dependency return UNAVAILABLE with no
privilege increase; do not return a partial negative conflict answer. A larger
bounded durable admission evaluation can be designed separately if necessary.

### 4. Material-context consistency and failure handling

The final admission verifies current material revisions/fingerprints through
owner-provided semantic queries. Re-read stable context if it changed during
evaluation; fail closed on incomplete/unstable context. An overlapping change
after the final successful current-context check is ordered after that admission
for policy decision purposes; already committed authority is not retroactively
rewritten. Identity epoch, Catalog availability and SPECIFIC guards still govern
semantic contribution. This does not introduce cross-capability database locks.
If an owner cannot expose an exact versioned read, that dependency is a blocker
to implementation; do not substitute timestamps or projection counts.

Use typed stable error outcomes, not provider strings. Expired/abandoned
reservation leases are fenced by generation and can be reclaimed. Retry,
duplication, stale delivery and restart preserve a single causal result.
Cancellation of a pending authorized request must be coordinated with the Access
reservation/result; once Access has committed, record APPLIED and use ordinary
Access reduction rather than claim cancellation prevented the assignment.

### 5. Evidence and scope

Governance owns all PolicyEvaluation, risk/SoD and ApprovalDecision evidence.
Access owns the admission reservation and application result; only stable
evidence IDs/fingerprints cross the boundary. Audit remains minimized. Tenant is
part of every key and lookup. No provider operation in admission transactions.

Lifecycle approval candidates additionally bind the current Identity epoch and
the material rule-match admission occurrence (not ordinary profile changes).
Rule reductions precede any new approval or admission. A review suppression
contract and exception-plan authority are separate GOV-V3-04/05 decisions.

## Implementation gates

- GOV-V3-02: implement typed authorization and approval context, expiry/epoch
  classification, causal replay-first recovery, and current revalidation.
- GOV-V3-03: implement Access-owned reservation + authoritative conflict query;
  wire every increase path; share typed SoD logic while preserving request and
  lifecycle default-decision semantics. Do not declare concurrent enforcement
  complete until all participating paths are fenced.
- Validate the exact versioned semantic read contract and reservation lifecycle
  with an implementation-level transaction/race review before accepting ADR.
- Tests: two concurrent independent conflicting requests; committed assignment
  with delayed projection; future overlap/nonoverlap; policy/exception/Role/epoch
  changes while queued; unavailable evaluator; stale lease; crash after Access
  commit; reduction during reservation; cross-tenant attempts; no provider call
  in transaction; budget overflow returns no partial authority.
