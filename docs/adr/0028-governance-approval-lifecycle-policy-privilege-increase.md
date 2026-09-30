# ADR-0028: Governance approval for lifecycle-policy privilege increases

Status: Accepted

## Context

ADR-0025 makes Access the owner of lifecycle/birthright policy and AccessAssignment mutation. New
policy-owned privilege must pass the Governance lifecycle privilege guard. AUTHORIZE creates access,
DENY leaves it absent, UNAVAILABLE retries, and REQUIRE_APPROVAL was deliberately deferred.

ADR-0016 already provides reusable typed Governance approval orchestration. ADR-0017 establishes the
important stale-approval rule: approval is evidence/authorization, not mutation authority, and
current mandatory governance context must be revalidated before privilege is applied.

The missing boundary is a typed lifecycle-access approval subject and a safe callback to Access.

## Decision

### Governance owns a typed lifecycle-access approval subject

Governance persists an immutable LifecycleAccessApprovalCandidate for each approval-required
lifecycle candidate. It contains:

- stable candidate ID;
- Identity ID;
- logical lifecycle rule ID;
- Access target kind and target ID;
- Governance PolicyVersion that produced REQUIRE_APPROVAL;
- approval-plan fingerprint;
- correlation/causation and creation time.

The reusable ApprovalCase gains the explicit subject kind LIFECYCLE_ACCESS_CANDIDATE and points to
this candidate ID. The candidate is evidence/context, not Access authority.

A causally equivalent current candidate reuses the same pending case. A materially different
Governance PolicyVersion/plan produces a new candidate/case; completed historical cases remain
immutable evidence.

### Approval plan comes from the current Governance policy

When the lifecycle guard returns REQUIRE_APPROVAL, Access invokes a consumer-defined semantic
LifecycleAccessApprovalCommand. Governance reuses the approval plan snapshotted in the current
ACTIVE ACCESS_REQUEST PolicyVersion that caused the mandatory guard result.

Governance never writes Access persistence.

### Approval resolution triggers Access revalidation

A final approval emits a data-minimized internal lifecycle-access approval-resolved fact. It carries
the candidate ID as trigger identity and normal correlation/causation metadata.

Access consumes the trigger and re-runs current lifecycle reconciliation for the candidate Identity.
It re-reads current ACTIVE lifecycle policy/rule, current Identity eligibility/canonical context,
current target state and the mandatory Governance guard.

A completed approval satisfies REQUIRE_APPROVAL only when the current Governance PolicyVersion and
approval-plan fingerprint equal the candidate's completed approval context. DENY or UNAVAILABLE
still wins. If the lifecycle rule no longer matches, the policy/target changed, or the Identity is
not ACTIVE, no assignment is created.

Approval therefore never directly creates an AccessAssignment and cannot resurrect stale Mover
intent.

### Rejection and reduction

Rejection leaves access absent. It emits no privilege-increase trigger.

Policy-owned reductions remain first and evaluator-independent. A pending or approved case cannot
block or reverse reduction.

### Replay and idempotency

Candidate/case creation is causally unique for the governed candidate context. Approval resolution
and Access reconciliation are at-least-once safe. AccessAssignment rule provenance remains the
authoritative duplicate fence for created access.

## Consequences

- lifecycle/birthright SoD approval uses the existing Governance approval engine;
- Governance retains approval/evaluation evidence without becoming Access authority;
- stale approvals are fenced by current-state and current-policy revalidation;
- reductions remain independent of approval availability;
- no generic BPM engine or second assignment authority is introduced.

## Deferred

- source-driven SUSPENDED -> ACTIVE without explicit suspension provenance;
- SPECIFIC Principal lifecycle grants;
- temporal lifecycle grants;
- compound/range/multi-valued predicates;
- tenant-wide retroactive policy sweep;
- dynamic manager/organization/ownership predicates.
