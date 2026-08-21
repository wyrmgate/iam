# State Machines and Invariants

## Core rule

Governance state, temporal validity, technical fulfillment and provider observation are separate dimensions. A single status field must not attempt to encode all of them.

## Universal invariants

- Time validity is semantic. Expiry becomes effective at `validUntil` even if a scheduler has not materialized an `EXPIRED` state yet.
- Terminal historical objects are not resurrected. Later business intent creates a successor/new assignment, exception, version or workflow round.
- Activated RoleVersion and PolicyVersion content is immutable; rollback creates a new version.
- Mutable authoritative aggregates use optimistic revision/version semantics where concurrent changes matter.
- Retriable mutation operations use durable idempotency identity where duplicate execution would be harmful.
- Idempotency deduplicates the same causal operation, not all semantically similar access.
- No external/technical failure rewrites a valid earlier governance decision.
- Incomplete discovery/import must never be interpreted as mass absence.
- Privilege-increasing operations fail closed when mandatory governance evaluation is unavailable. Valid privilege reduction/revocation must not be blocked by unrelated evaluator failure after the authoritative trigger exists.
- No cross-capability business cascade delete.
- Historical references remain readable after referenced objects retire.

## Identity

Lifecycle states are independent from canonical-record state. Normal lifecycle supports PENDING, ACTIVE, SUSPENDED, INACTIVE and DECOMMISSIONED semantics. A merged record becomes a historical alias to the survivor rather than using `MERGED` as employment/lifecycle state.

Identity merge/split is an explicit durable operation because it may affect source links, principals, assignments, administrative grants, ownership, requests/reviews, SoD and risk. Merge preserves provenance; historical assignment identity is not silently rewritten.

## Role and policy versions

Recommended lifecycle:

```text
DRAFT → VALIDATING → READY → ACTIVE → SUPERSEDED
           ↘ REJECTED   ↘ CANCELLED
```

An active usable Role/Policy has exactly one active version. Superseded content is never mutated or reactivated; a rollback publishes a new version.

## AccessAssignment

Recommended lifecycle:

```text
PENDING_ACTIVATION → ACTIVE → PENDING_REVOCATION → REVOKED
                         ↕
                     SUSPENDED

PENDING_ACTIVATION/ACTIVE/SUSPENDED → EXPIRED
PENDING_ACTIVATION → CANCELLED
```

`CANCELLED`, `EXPIRED` and `REVOKED` are terminal. Access effectiveness additionally evaluates the ValidityWindow and relevant identity/catalog state.

Integration fulfillment is not stored as assignment business state. A legitimate composite view may therefore be:

```text
Assignment = EXPIRED
Fulfillment = FAILED_REVOCATION
ObservedGrant = PRESENT
```

## Request and approval

RequestItem is the governable unit:

```text
DRAFT → SUBMITTED → EVALUATING → PENDING_APPROVAL → AUTHORIZED → APPLIED
                         ↘ DENIED       ↘ REJECTED
```

Unfinished items may also become CANCELLED or EXPIRED. `AUTHORIZED` means governance requirements succeeded; `APPLIED` means Access accepted the authoritative change. Neither means provider provisioning succeeded.

Before final authorization, material identity/role/policy/risk/SoD dependency revisions are revalidated. Stale context may supersede an ApprovalPlan and cause reevaluation.

ApprovalPlan is an immutable workflow snapshot. ApprovalDecision is immutable evidence.

## Review

Campaign state is independent of remediation completion. ReviewItem is a scalable consistency boundary rather than a child collection loaded through ReviewCampaign.

ReviewDecision is immutable evidence. ReviewRemediation always reads current authoritative state and may finish as APPLIED, NO_ACTION_REQUIRED, FAILED or MANUAL_REQUIRED. A review snapshot can never resurrect access already removed elsewhere.

## GovernanceException and finding

Exception is effective only while both its lifecycle state and ValidityWindow are valid. Expiry does not disappear because a scheduler is late, and renewal creates a successor exception.

Finding lifecycle distinguishes unresolved, acknowledged/remediation-pending, mitigated/accepted and truly resolved states. `MITIGATED` or `ACCEPTED` can reopen if compensating controls/exception coverage cease. `RESOLVED` means the condition actually disappeared.

## Credential

Credential does not use `ROTATING` as a lifecycle state. Rotation is an orchestration involving replacement/cutover/revocation.

Typical credential progression:

```text
PENDING_ACTIVATION → ACTIVE → PENDING_REVOCATION → REVOKED
                         ↘ EXPIRED
                         ↘ COMPROMISED
```

A compromised credential stops being considered safe immediately even when external revocation has not succeeded yet.

## Provisioning

ProvisioningTask:

```text
PENDING → READY → RUNNING → SUCCEEDED
                    ↘ FAILED_RETRYABLE → READY
                    ↘ FAILED_FINAL
                    ↘ MANUAL_REQUIRED

PENDING/READY → SKIPPED | BLOCKED | SUPERSEDED
```

Each execution creates immutable ProvisioningAttempt evidence. Tasks carry desired revision and idempotency identity. Before execution, stale tasks are revalidated and become SUPERSEDED/no-op when no longer required.

Task dependency edges remain within one ProvisioningJob and form an acyclic DAG.

## Reconciliation and import completeness

Run outcome and completeness are distinct. `COMPLETED` does not automatically mean it is safe to infer that absent objects were deleted.

Destructive absence inference requires a complete trustworthy run and connector/source semantics that support absence detection. Positive authoritative facts such as an explicit termination may be processed even when the larger run later becomes partial; inferred absence requires stronger evidence.

## Administrative authorization

Administrative grants/delegations are checked at operation time against state, ValidityWindow, actor identity state, scope, policy and required authentication assurance. An authenticated session does not extend an expired grant.

Delegation stops being effective when the delegator loses the delegated authority. Administration-role editing/granting cannot be used to self-escalate beyond the actor's grantable authority. Self-approval of administrative elevation is denied by default.

## Structural/cardinality highlights

- Identity owns exactly one profile compatible with IdentityType.
- SourceRecord has at most one active accepted IdentityLink.
- Principal belongs to at most one Identity.
- Credential belongs to exactly one Principal.
- Organization primary hierarchy is acyclic.
- ApplicationTarget belongs to exactly one Application.
- Entitlement belongs to one Application and optionally one compatible Target.
- APPLICATION Role belongs to one Application; BUSINESS Role may span applications.
- AccessAssignment belongs to one Identity and targets one Role or Entitlement.
- A submitted AccessRequest has one beneficiary and at least one RequestItem.
- A RequestItem has at most one current active ApprovalPlan but may retain superseded historical plans.
- ConnectorBinding separates SourceSystem/ApplicationTarget from ConnectorInstance implementation.
- No normal domain relationship crosses an enabled tenant/isolation boundary.
