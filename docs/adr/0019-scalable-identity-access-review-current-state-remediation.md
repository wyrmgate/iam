# ADR-0019: Scalable Identity access review and current-state remediation

Status: Accepted

## Context

Wyrmgate IAM v0.3 requires scalable access reviews with immutable reviewer decisions and
remediation against current authoritative state. The current runtime already has:

- authoritative AccessAssignment state in Access;
- reusable Governance approval and GovernanceException orchestration;
- transactional outbox and retryable worker infrastructure;
- explicit separation between authoritative access, EffectiveAccess, desired state and
  provider observation/fulfillment.

A review snapshot is historical evidence. It must not become mutation authority over
Access state that may have changed after the campaign was generated.

## Decision

The first review runtime is Governance-owned `IDENTITY_ACCESS` review.

### Campaign scope

A ReviewCampaign binds:

- one governed subject Identity;
- one explicit governed reviewer Identity;
- one fixed `snapshotAt` instant;
- one lifecycle/revision;
- a durable generation continuation checkpoint.

The subject Identity and reviewer Identity must both exist in the tenant. The first slice
does not allow self-review.

Initial lifecycle:

```text
DRAFT -> GENERATING -> ACTIVE -> COMPLETED
                    \-> FAILED
```

`COMPLETED` means every generated ReviewItem has one immutable ReviewDecision. It does
not mean technical remediation or provider fulfillment is complete.

### Access-owned review snapshot query

Governance never reads Access persistence directly.

Access exposes a semantic `AccessReviewSnapshotQuery` that pages authoritative
AccessAssignments for one Identity at the campaign's fixed snapshot instant.

Paging order is deterministic:

```text
createdAt ASC, id ASC
```

The query returns only assignments whose authoritative intent was non-terminal at the
snapshot instant and whose validity had not ended:

- ACTIVE;
- SUSPENDED;
- SCHEDULED.

The snapshot query returns the assignment stable ID, snapshot revision, target semantics,
principal constraint, provenance, validity and created timestamp needed as immutable
review context.

Review generation is durable and resumable. Governance stores the last Access continuation
position after each committed page. ReviewItem uniqueness on campaign + AccessAssignment
makes replay/duplicate generation safe.

ReviewItem is the scalable consistency boundary; the campaign aggregate never loads an
unbounded item collection.

### Decisions

A ReviewItem is assigned to the campaign reviewer and moves:

```text
PENDING -> DECIDED
```

The first decision values are:

- `KEEP`;
- `REVOKE`.

ReviewDecision is immutable append-only evidence. Exactly one decision is accepted per
ReviewItem, only from the assigned reviewer, with optimistic ReviewItem revision.

`KEEP` creates no Access mutation work.

`REVOKE` creates exactly one Governance-owned ReviewRemediation record and one durable
`governance.review-remediation-requested` fact in the same transaction as the decision.

### Remediation state

ReviewRemediation lifecycle is:

```text
PENDING -> APPLIED
        -> NO_ACTION_REQUIRED
        -> FAILED
        -> MANUAL_REQUIRED
```

Remediation result is separate from ReviewDecision and ReviewCampaign completion.

### Access-owned remediation command

Governance invokes an Access-owned semantic `AccessReviewRemediationCommand` outside the
Governance transaction.

The command receives:

- stable `reviewRemediationId` as causal idempotency identity;
- AccessAssignment ID.

Access re-reads current authoritative AccessAssignment state.

If current authority still requires removal:

- ACTIVE/SUSPENDED -> REVOKED;
- future SCHEDULED -> CANCELLED;
- SCHEDULED whose start is already due is terminated according to current Access semantics.

If the assignment is already absent or terminal
(`REVOKED`, `EXPIRED`, `CANCELLED`), the command returns
`NO_ACTION_REQUIRED`.

Access stores a small Access-owned remediation-application result keyed uniquely by
`reviewRemediationId`. Therefore if Access commits and Governance crashes before
recording the result, retry returns the same Access result rather than reinterpreting the
new terminal state as a different outcome.

Governance stores the result and stable AccessAssignment ID only. There is no
cross-capability database foreign key.

Provider fulfillment remains downstream. `ReviewRemediation.APPLIED` means Access
accepted the authoritative termination; it does not mean provider access has been removed.

### Snapshot safety

ReviewItem snapshot revision and target/provenance fields are evidence only.

Remediation never attempts to restore, rewrite or compare-and-set the historical snapshot
revision. It re-reads current Access state and applies only a semantic reduction. Therefore
a stale review snapshot cannot resurrect access or overwrite newer Access changes.

### Delivery semantics

Campaign generation and remediation processing assume at-least-once delivery.

Generation retries are safe because:

- campaign continuation is durable;
- item insert is unique on campaign + assignment;
- pages are deterministically ordered.

Remediation retries are safe because:

- Governance remediation is one-per-ReviewItem;
- Access application is unique by reviewRemediationId;
- stale/duplicate outbox deliveries re-read current Governance state.

## Consequences

- Reviews remain Governance business/process state while Access remains the only owner of
  AccessAssignment mutation.
- Campaigns scale without loading all review items in one aggregate transaction.
- Campaign completion, remediation completion and provider fulfillment are explicitly
  different dimensions.
- Reviewer decisions remain immutable evidence.
- Retry after partial cross-capability success is deterministic.
- The first slice intentionally uses one subject Identity and one explicit reviewer;
  application/role/organization campaigns and dynamic reviewer selection are later typed
  extensions.

## Deferred

- public Review HTTP API;
- campaign schedules/recurrence;
- manager/owner/dynamic reviewer selection;
- application/role/organization review scopes;
- multi-reviewer/quorum review decisions;
- reminders/escalation/delegation;
- Review of raw EffectiveAccess rows independent of AccessAssignment authority;
- automatic Review creation from GovernanceException expiry or findings;
- provider-remediation fulfillment as campaign lifecycle state.
