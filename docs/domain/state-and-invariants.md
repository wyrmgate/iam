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

### Canonical attribute resolution

Dynamic canonical Identity attributes remain distinct from SourceRecord observation. `AttributeDefinition` is stable identity/key; semantic type/cardinality/classification/query-policy flags are versioned within immutable activated canonical schema versions. Mapping and authority are separately versioned concerns.

Canonical resolution outcomes are explicit:

- `RESOLVED` — one effective top-authority value set is selected with candidate and authority provenance.
- `OVERRIDDEN` — an effective governed override supplies the canonical value; source observations/candidates remain unchanged.
- `CONFLICT` — multiple equally authoritative current candidates disagree. A prior compatible source-resolved trusted value may remain readable while the conflict is exposed; conflict never silently falls back to source recency.
- `UNRESOLVED` — candidates exist but no compatible active authority rule can select one. A prior compatible source-resolved trusted value may remain readable while degraded resolution is exposed.
- `NO_VALUE` — no current candidate exists for the active definition version and no effective override applies.

Source observation time is provenance, not authority. Last-write-wins is not a default resolution strategy. Equal authority with equal normalized values is not a value conflict; selection of equivalent provenance is deterministic but does not change the canonical value.

Overrides obey semantic validity directly. An override ceases to govern at `validUntil` even if no scheduler runs, and an expired override is not retained indirectly as the trusted fallback for a later conflict/unresolved condition. Applying or replacing an override preserves override history and never rewrites SourceRecord or candidate data.

Canonical `valueRevision` changes only when the effective resolution outcome, selected provenance or canonical value set changes. Re-running resolution with the same outcome is a no-op so raw source churn with no canonical impact stops before downstream lifecycle/access reevaluation.

Activated canonical schema content is immutable. A replacement schema creates new definition versions; mappings, authority rules, candidates and overrides tied to an older definition version do not silently become compatible with the new version.

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
SCHEDULED → ACTIVE
               ↕
           SUSPENDED

SCHEDULED/ACTIVE/SUSPENDED → REVOKED
SCHEDULED/ACTIVE/SUSPENDED → EXPIRED
SCHEDULED → CANCELLED
```

`CANCELLED`, `EXPIRED` and `REVOKED` are terminal. `SCHEDULED` means the assignment exists as authoritative intent but is not yet effective because its valid-from time has not arrived. Technical provisioning or revocation progress is never encoded as assignment business state.

Once revocation is authoritatively decided, the assignment becomes `REVOKED` immediately and no longer contributes to EffectiveAccess. Provider-side removal is tracked independently through fulfillment and observations. A legitimate composite view may therefore be:

```text
Assignment = REVOKED
Fulfillment = FAILED
ObservedGrant = PRESENT
```

This is visible drift, not an ambiguous still-pending governance state.

The first runtime AccessAssignment slice implements Entitlement targets with explicit `MANUAL` provenance and `ANY`/`SPECIFIC` principal constraints. Creation validates foreign references through semantic Identity/Catalog queries and never reads their repositories directly. A future `SCHEDULED` assignment is not effective before `validFrom`, but if materialized state still says `SCHEDULED` after that instant, semantic evaluation treats the reached validity window as effective. Conversely, `validUntil` ends authority immediately even before an `EXPIRED` state is materialized. Explicit termination uses optimistic revision and chooses `CANCELLED` before a scheduled start, `EXPIRED` after the validity window, and otherwise `REVOKED`.

EffectiveAccess is a rebuildable projection, never assignment authority. Direct Entitlement assignments contribute one normalized support path while semantically effective. Multiple assignments may support the same Identity + Entitlement + principal-constraint tuple; removing one support leaves the effective row while another support remains, and removing the final support removes the effective row. Projection-input replay is idempotent. Valid-from/valid-until timers repair materialized state around time boundaries, while semantic EffectiveAccess reads still evaluate current AccessAssignment validity directly so timer delay cannot change authorization meaning.

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

Credential does not use `ROTATING` or `PENDING_REVOCATION` as lifecycle states. Rotation/revocation technical progress is an orchestration/fulfillment concern.

Typical credential progression:

```text
SCHEDULED? → ACTIVE → REVOKED
                 ↘ EXPIRED
                 ↘ COMPROMISED
```

`SCHEDULED` is optional where a credential has a future activation time. A compromised credential stops being considered safe immediately even when external revocation has not succeeded yet. Once revocation is authoritatively decided, the Credential is `REVOKED`; any provider residue is tracked as observation/finding/remediation state.

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
