# ADR-0018: Scoped time-bound GovernanceException lifecycle

Status: Accepted

## Context

ADR-0017 introduced typed ACCESS_REQUEST PolicyVersion, SoDRule, RiskAssessment,
SoDConflict and PolicyEvaluation semantics. The formal v0.3 baseline also requires
GovernanceException to be explicit, scoped and time-bound and to never delete the
underlying violation.

A generic waiver flag on PolicyEvaluation, SoDConflict or AccessRequest would collapse
authority and evidence, would be hard to expire safely, and could silently carry across a
replacement PolicyVersion.

## Decision

Governance owns a first-class `GovernanceException` authoritative aggregate.

### Initial scope

The first supported scope kind is `IDENTITY_SOD_RULE`.

An exception binds exactly:

- one governed Identity stable ID;
- one exact Governance SoDRule ID, and therefore one immutable PolicyVersion;
- one requester/initiator Identity;
- one business reason;
- one validity window `validFrom <= now < validUntil`;
- one reusable ApprovalCase;
- optionally one predecessor GovernanceException for renewal.

There are no wildcard, tenant-wide, organization-wide, application-wide or arbitrary JSON
exception scopes in the first slice.

Because the exception references an exact SoDRule, a successor PolicyVersion does not
inherit the exception even when a human-readable rule code is reused.

### Lifecycle and temporal authority

The authoritative lifecycle is:

```text
PENDING_APPROVAL -> APPROVED | REJECTED
APPROVED -> REVOKED | EXPIRED
```

An APPROVED exception is effective only while its validity window is effective.
Scheduler delay never extends authority beyond `validUntil`.

REJECTED, REVOKED and EXPIRED are terminal. Revocation is immediate authority reduction
and does not depend on policy/risk evaluator availability.

Renewal creates a new GovernanceException referencing the predecessor. The predecessor's
validity and evidence are not edited. The first slice requires renewal validity to begin
at or after the predecessor's `validUntil`, preventing two effective successor/predecessor
records from representing the same renewal chain at the same instant.

### Approval

Every GovernanceException begins in PENDING_APPROVAL and starts a reusable ApprovalCase
with subject kind `GOVERNANCE_EXCEPTION`.

The reusable approval kernel remains subject-neutral. GovernanceException consumes the
terminal result through its own ApprovalResultSink:

- APPROVED -> GovernanceException becomes APPROVED;
- REJECTED -> GovernanceException becomes REJECTED.

Approval is necessary but not sufficient for effectiveness; the validity window remains a
separate semantic condition.

### Policy/risk/SoD integration

ADR-0017 evaluation continues to persist every matched SoDConflict.

For each matched SoDRule, Governance performs a bounded semantic exception query for the
beneficiary Identity + exact SoDRule ID at evaluation time.

If an effective approved exception covers a matched rule:

- the SoDConflict is still persisted;
- RiskAssessment still includes the factor and severity;
- the SoDConflict evidence records the applied GovernanceException ID;
- that rule's enforcement action is waived for that evaluation.

The PolicyVersion default decision is never waived by an IDENTITY_SOD_RULE exception.
Other uncovered rule actions still apply normally.

Thus exception changes policy handling but never erases the violation or risk evidence.

### Expiry and change facts

On approval, Governance schedules a technical validity-boundary work item for
`validUntil`. Technical scheduling is not authority.

At or after `validUntil`, a retryable Governance processor may materialize EXPIRED
idempotently if the exception is still APPROVED. The exception is already semantically
ineffective at the instant `validUntil` even before materialization.

Approval, rejection, revocation and expiry append a data-minimized internal
`governance.exception-changed` fact atomically with the exception state change. The fact
contains only the exception aggregate reference/revision and instructs downstream
Governance remediation/review consumers to re-read current state.

This first slice does not directly mutate AccessAssignment or provider state when an
exception expires or is revoked. Automatic remediation of previously applied access is a
later typed remediation/review workflow.

### Revalidation

AccessRequest final approval already re-runs ADR-0017 evaluation. The same exception query
is therefore re-evaluated before authorization.

An exception that expired, was revoked, or belongs to a replaced SoDRule cannot be used by
stale approval evidence to authorize new access.

## Consequences

- Exception authority is explicit, reviewable and time-bounded.
- Underlying SoDConflict/RiskAssessment evidence remains intact.
- Successor policy versions do not inherit exceptions implicitly.
- Renewal preserves history and creates a successor aggregate.
- Revocation is fail-open only toward authority reduction: exception coverage disappears
  immediately without waiting for unrelated evaluators.
- Timer delivery is repair/materialization only; time semantics define authority.
- The reusable approval kernel remains generic and subject-neutral.
- Public GovernanceException administration APIs, broader scope kinds and automatic access
  remediation are intentionally deferred.
