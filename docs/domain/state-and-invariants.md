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

### Source-driven correlation and positive construction

ADR-0022 makes positive source automation explicit and fail-safe. An activated source-correlation policy references the exact active mapping for one `STRING`/`SINGLE` canonical key and requires active authority for that source/key. Zero, one and multiple current `RESOLVED`/`OVERRIDDEN` canonical matches mean no-match, unique match and ambiguity respectively; ambiguity never guesses. Existing accepted links are sticky for automation. Policy-authorized no-match creation begins as `PENDING` and materializes the correlation key under a short creation fence before the fence is released, so concurrent positive imports converge on the same governed key rather than creating duplicate Identities.

Canonical candidates are applicable only while their SourceRecord has a current accepted link to the candidate Identity. Explicit link replacement retains candidate/link history as provenance but removes the old candidate from current authority and triggers affected canonical re-resolution. Positive observation processing is independent from destructive absence: COMPLETE/PARTIAL/UNKNOWN import semantics are not used to infer termination in this slice.

## Role and policy versions

Recommended lifecycle:

```text
DRAFT → VALIDATING → READY → ACTIVE → SUPERSEDED
           ↘ REJECTED   ↘ CANCELLED
```

An active usable Role/Policy has exactly one active version. Superseded content is never mutated or reactivated; a rollback publishes a new version.

The first Role runtime slice keeps the accepted shallow graph explicit. An APPLICATION Role belongs to one Application and its RoleVersion contains only active target-scoped Entitlements from that same Application. A BUSINESS Role may contain active target-scoped Entitlements and APPLICATION Roles, but not BUSINESS Roles. BUSINESS expansion resolves each member APPLICATION Role through that child's current ACTIVE RoleVersion at expansion time, so a child version activation can change the effective expansion of an otherwise unchanged BUSINESS Role. Catalog emits a semantic expansion-change fact for the changed Role and any currently active BUSINESS parents.

RoleVersion membership is immutable after activation and after supersession. RoleVersion lifecycle is an authoritative concurrency boundary with its own positive revision: validation and activation are revision-guarded, and superseding the prior ACTIVE version advances that prior version's revision as part of the same activation transaction. Activation revalidates current member state and atomically supersedes the prior ACTIVE version so there is at most one ACTIVE RoleVersion per Role. A Role with no valid current expansion is not assignable for new access and contributes no role-derived EffectiveAccess until the expansion becomes valid again.


### Trusted source absence inference

Destructive source absence is distinct from positive lifecycle facts. COMPLETE coverage alone is insufficient: the completed SourceImportRun must carry explicit TRUSTED absence evidence and the SourceSystem must have an active versioned SourceAbsencePolicy. The first policy maps inferred absence only to INACTIVE and fixes a maximum automatic inferred-transition count per import. SourceAbsenceInference is Identity-owned durable process state with stable SourceRecord checkpoints. Every candidate re-reads current import authority, policy, SourceRecord, accepted IdentityLink and Identity. A newer import, changed policy, positive observation at/after the trusted run start, or post-snapshot relink fences stale destructive work. Reaching the configured transition ceiling changes the process to MANUAL_REQUIRED before exceeding it. Identity lifecycle mutation still goes only through IdentityCommandService and ADR-0021 Access reduction.

### Identity merge and split

Identity merge/split is explicit administrative correction, not inferred reconciliation. Merge selects one same-type survivor and decommissions the absorbed Identity after re-homing only Identity-owned current source links and Principals; historical Identity IDs and superseded link rows remain intact. AccessAssignments and other foreign-capability authority are never moved. Split creates a same-type PENDING Identity and moves only explicitly selected current source links/Principals after validating current ownership atomically. Canonical candidate/state/override history is never physically rewritten, and manual overrides are not copied. Principal reassignment increments Principal revision and causes Access to re-evaluate Principal-dependent desired state for both previous and new owners.

### Source-driven Identity lifecycle policy

An active Identity-owned SourceLifecyclePolicyVersion may map one bounded explicit source field to typed lifecycle intent for currently correlated SourceRecords. Automatic PENDING -> ACTIVE is allowed. INACTIVE -> ACTIVE remains allowed only for ADR-0027 exact trusted-absence transition evidence. ADR-0038 additionally permits SUSPENDED -> ACTIVE only when immutable SourceSuspensionTransitionEvidence proves that this same current SourceRecord/current accepted IdentityLink caused the exact current SUSPENDED Identity revision; operator/IAM suspension, relink, another SourceRecord or any intervening Identity revision remains protected. Explicit mapped reductions use only the normal Identity lifecycle command, so ACTIVE eligibility loss reuses ADR-0021 and Access-owned durable reduction. Missing/unmapped values are no-op. Positive explicit termination/suspension facts do not require COMPLETE import coverage; inferred absence remains separately COMPLETE-gated and is not implemented by this policy.

### Lifecycle access policy and Mover reconciliation

Access owns versioned lifecycle/birthright policy because the policy creates and removes authoritative AccessAssignment intent. The bounded rules are ALWAYS, exact policy-addressable SINGLE canonical equality for STRING, BOOLEAN, INTEGER, ENUM, DECIMAL, DATE and DATETIME, or typed CONTAINS membership over policy-addressable MULTI attributes of those same types; they target one Role or Entitlement and use ANY principal semantics. DECIMAL equality/membership is normalized numeric equality, DATE is exact civil-date equality, and DATETIME is exact absolute-Instant equality at persisted microsecond precision. MULTI order, ordinal position and duplicate count do not affect membership. Ordering/ranges, set algebra, compound expressions and relationship predicates remain outside this slice. Stable logical rule UUID is AccessAssignment provenance. Reconciliation re-reads current ACTIVE Identity/canonical context on every dedicated Identity trigger. Policy-owned removals happen before any privilege increase and cannot be blocked by Governance unavailability. New policy assignments require the mandatory Governance lifecycle privilege guard; only AUTHORIZE creates access, REQUIRE_APPROVAL/DENY leave access absent, and UNAVAILABLE fails closed/retries. Each guard attempt persists immutable Governance evidence identifying the candidate rule/target, active policy version when available, matched SoD conflicts, exact exception coverage and end-to-end correlation/causation. Manual and REQUEST_ITEM assignments are independent support and are never revoked merely because lifecycle-policy support disappears.

### Identity lifecycle eligibility and Leaver reduction

Identity lifecycle is authoritative in Identity and is distinct from AccessAssignment lifecycle. In the first lifecycle policy only `ACTIVE` is access-eligible; `PENDING`, `SUSPENDED`, `INACTIVE` and `DECOMMISSIONED` are access-ineligible. EffectiveAccess semantic reads must therefore re-check Identity access eligibility and return no authority for an ineligible Identity even while derived rows or non-terminal AccessAssignments still exist.

A transition from access-eligible to access-ineligible emits a minimized semantic fact atomically with the Identity revision change. Access owns the resulting durable reduction process and never permits Identity to mutate Access tables. New AccessAssignment creation requires an access-eligible Identity. Existing non-terminal assignments at the reduction snapshot are terminated with the ordinary Access semantics: future scheduled access becomes `CANCELLED`, already-ended validity becomes `EXPIRED`, and otherwise the assignment becomes `REVOKED`. Terminal assignments remain terminal.

Reduction processing is bounded and resumable. Its continuation is ordered by assignment `createdAt + id` and is causally unique by tenant + Identity + source Identity revision. Before every page, Access re-reads current Identity eligibility; a stale reduction completes without further mutation after reactivation. The fixed source-fact snapshot also excludes assignments created after the old reduction event. Provider or evaluator failure cannot restore eligibility or a terminated AccessAssignment.

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

The current AccessAssignment runtime supports Entitlement and Role targets with explicit `MANUAL` provenance and `ANY`/`SPECIFIC` principal constraints. Creation validates foreign references through semantic Identity/Catalog queries and never reads their repositories directly. A future `SCHEDULED` assignment is not effective before `validFrom`, but if materialized state still says `SCHEDULED` after that instant, semantic evaluation treats the reached validity window as effective. Conversely, `validUntil` ends authority immediately even before an `EXPIRED` state is materialized. Internal generic termination uses optimistic revision and chooses `CANCELLED` before a scheduled start, `EXPIRED` after the validity window, and otherwise `REVOKED`. The public control-plane contract exposes the narrower semantic operations suspend, resume, cancel and revoke rather than generic status mutation. Suspend is ACTIVE -> SUSPENDED; resume is allowed only from SUSPENDED while temporally valid; cancel applies only to a future SCHEDULED assignment; revoke applies only to ACTIVE/SUSPENDED and is authoritative immediately.

EffectiveAccess is a rebuildable projection, never assignment authority. Direct Entitlement assignments contribute one normalized support path while semantically effective. Role assignments contribute one support per current entitlement derivation path and retain the exact ordered RoleVersion path used for explanation. Multiple assignments or multiple role paths may support the same Identity + Entitlement + principal-constraint tuple; removing one support leaves the effective row while another support remains, and removing the final support removes the effective row. A RoleVersion activation replaces only the affected assignment's role-derived support set; obsolete paths are removed and new current paths are added idempotently. Projection-input replay is idempotent. Valid-from/valid-until timers repair materialized state around time boundaries, while semantic EffectiveAccess reads still evaluate current AccessAssignment validity directly so timer delay cannot change authorization meaning.

Role-target AccessAssignment now uses the same lifecycle, validity and MANUAL provenance semantics as direct Entitlement assignments. ANY is valid for any current Role expansion. SPECIFIC is accepted only when the full current expansion resolves to exactly one ApplicationTarget and the governed Principal belongs to that Identity and target; a multi-target BUSINESS Role cannot guess which technical Principal should realize the assignment.

DesiredPrincipalState and DesiredGrantState are rebuildable technical-intent projections over EffectiveAccess, not governance authority. A desired grant keeps a stable tuple identity across PRESENT/ABSENT transitions. `desiredRevision` changes only when desired presence or resolved Principal changes; replay/recompute may advance source generation without changing desired revision. `ANY` principal realization is deterministic: one active correlated Principal for the Identity + ApplicationTarget resolves, while zero or multiple active Principals leave the grant unresolved. `SPECIFIC` preserves the explicitly governed Principal. An unresolved desired grant does not authorize guessing a provider account.

A DesiredGrantState revision may trigger Integration provisioning planning, but the planning fact is only a notification to re-read current desired state. ADD_GRANT planning requires one resolved active Principal and exactly one safe provider entitlement target; missing or ambiguous technical resolution fails closed and is retried. REMOVE_GRANT planning may fan out to all safely known current or previously successful technical grant targets. Integration task state, provider failure and retry never rewrite AccessAssignment or desired governance intent. Duplicate/replayed planning facts are absorbed by deterministic Integration plan/task idempotency identities.

A DesiredPrincipalState revision may likewise trigger provider-neutral UPSERT_PRINCIPAL or DISABLE_PRINCIPAL planning. A first-time privilege increase requires exactly one active principal-capable route plus a bounded Identity-owned provisioning profile; the provider account name remains connector-owned technical configuration rather than a new canonical Identity username. Successful provider mutation does not let Integration create or mutate Identity.Principal directly: Integration emits a data-minimized internal completion fact and Identity idempotently materializes or changes Principal lifecycle. Principal lifecycle changes cause Access to re-evaluate ANY-principal realization and explicitly retrigger principal planning against the same current desired revision when needed, so a late stale provider completion converges back to current intent without inventing a false desired revision.

## Request and approval

RequestItem is the governable unit:

```text
DRAFT → SUBMITTED → EVALUATING → PENDING_APPROVAL → AUTHORIZED → APPLIED
                         ↘ DENIED       ↘ REJECTED
```

Unfinished items may also become CANCELLED or EXPIRED. `AUTHORIZED` means governance requirements succeeded; `APPLIED` means Access accepted the authoritative change. Neither means provider provisioning succeeded.

Submitting an AccessRequest is one caller-visible operation. Each RequestItem transition to `SUBMITTED` durably emits internal evaluation work in the same transaction. The evaluator consumer may move the item to `EVALUATING`, `PENDING_APPROVAL`, `AUTHORIZED` or `DENIED`; mandatory evaluator outage remains `EVALUATING` and is retried. A public client never drives arbitrary evaluation or status mutation.

Before final authorization, material identity/role/policy/risk/SoD dependency revisions are revalidated. The first concrete ACCESS_REQUEST evaluator uses the active immutable PolicyVersion, current Role expansion, other original RequestItems and a bounded Access-owned current-EffectiveAccess query. Missing mandatory policy/query context remains EVALUATING and is retried. A completed approval authorizes only when revalidation now authorizes directly or requires the same immutable approval-plan fingerprint; a changed plan creates a new ApprovalCase, DENY moves the item to DENIED, and evaluator unavailability returns it to durable EVALUATING retry. Stale approval evidence is never current-state mutation authority.

ApprovalCase is reusable typed Governance process state for one subject. Its plan, stages and resolved approvers are immutable snapshots. Stages execute sequentially; ANY_ONE completes after one approval, ALL completes only after all snapshotted approvers approve, and any valid rejection rejects the case. Only a resolved approver for the current stage may decide. Self-approval by the case initiator is denied by default. ApprovalDecision is append-only immutable evidence. An approved case does not itself mutate the subject; the subject-owning flow consumes the result through a semantic boundary.

For AccessRequest, evaluator unavailability during privilege increase leaves the RequestItem in EVALUATING rather than authorizing it. An eligible item may become PENDING_APPROVAL and bind to an ApprovalCase. APPROVED advances the item to AUTHORIZED; REJECTED advances it to REJECTED.

An AUTHORIZED RequestItem carries explicit Role/Entitlement target, ANY/SPECIFIC principal constraint and optional validity window. The transition that records AUTHORIZED also appends a durable Governance outbox fact. A separate retryable consumer re-reads current Governance state and invokes the Access-owned `AccessIntentCommand` outside the Governance transaction. Access validates Identity/Catalog/Role/Principal semantics and creates exactly one `REQUEST_ITEM`-provenance AccessAssignment per RequestItem. Only after Access accepts that intent does Governance record the stable AccessAssignment ID and transition the item to APPLIED. Access/application failure leaves the approved item AUTHORIZED; duplicate or replayed work resolves the same assignment by provenance. There is no cross-capability database foreign key and no distributed Governance+Access transaction. APPLIED still does not mean provider fulfillment succeeded.

## Review

The first campaign lifecycle is `DRAFT -> GENERATING -> ACTIVE -> COMPLETED`, with `FAILED` reserved for terminal generation failure. `IDENTITY_ACCESS` binds one subject Identity, one distinct explicit reviewer Identity and one fixed `snapshotAt` cutoff. Generation pages Access through the Access-owned semantic review query using deterministic `createdAt + id` continuation; each committed page advances a durable checkpoint and creates independent ReviewItems. Campaign code never loads an unbounded ReviewItem collection.

ReviewItem lifecycle is `PENDING -> DECIDED`. Only its assigned reviewer may record one immutable `KEEP` or `REVOKE` ReviewDecision, using optimistic item revision. `KEEP` creates no access mutation. `REVOKE` atomically creates one `ReviewRemediation` plus durable remediation work.

Campaign `COMPLETED` means all generated items have decisions. It is independent of remediation completion and provider fulfillment. ReviewRemediation always re-reads current Access authority through an Access-owned semantic command and may finish `APPLIED`, `NO_ACTION_REQUIRED`, `FAILED` or `MANUAL_REQUIRED`. Access application is causally idempotent by ReviewRemediation ID, so retry after an uncertain Governance commit returns the same Access result. A historical review snapshot can never resurrect access or overwrite newer Access state.

## GovernanceException and finding

The first GovernanceException lifecycle is `PENDING_APPROVAL -> APPROVED | REJECTED` and `APPROVED -> REVOKED | EXPIRED`. Approval is necessary but not sufficient: an exception is effective only while state is APPROVED and `validFrom <= now < validUntil`. Expiry does not disappear because a scheduler is late. Revocation is immediate authority reduction and is not blocked by evaluator failure. Renewal creates a successor exception with the same Identity + exact SoDRule scope and a non-overlapping validity window; it never edits or extends predecessor history. Because scope references the exact immutable SoDRule, replacement PolicyVersions do not inherit old exceptions. Policy/SoD evaluation still persists every conflict and risk factor; covered conflicts retain the exact GovernanceException evidence reference while their rule enforcement action is waived.

Finding lifecycle distinguishes unresolved, acknowledged/remediation-pending, mitigated/accepted and truly resolved states. `MITIGATED` or `ACCEPTED` can reopen if compensating controls/exception coverage cease. `RESOLVED` means the condition actually disappeared.

## Credential

Credential does not use `ROTATING` or `PENDING_REVOCATION` as lifecycle states. Rotation/revocation technical progress is an orchestration/fulfillment concern.

Typical credential progression:

```text
SCHEDULED? → ACTIVE → REVOKED
                 ↘ EXPIRED
                 ↘ COMPROMISED
```

`SCHEDULED` is used when a credential has a future activation time. A Credential is semantically effective only while state is `ACTIVE`, `validFrom` is absent or reached, and `validUntil` is absent or not yet reached. Platform scheduled work may materialize SCHEDULED → ACTIVE and SCHEDULED/ACTIVE → EXPIRED, but scheduler delay never extends validity. A compromised credential stops being considered safe immediately even when external revocation has not succeeded yet. Once revocation is authoritatively decided, the Credential is `REVOKED`; any provider residue is tracked as observation/finding/remediation state.

The first CredentialRotation runtime keeps process state separate from Credential lifecycle. Routine rotation begins only from an effective ACTIVE old Credential. A replacement is attached once, must belong to the same Principal, and must be effective before cutover can become complete. Rotation cannot become COMPLETED while the old Credential is still effective. Failure exits are durable terminal process states and never restore or rewrite Credential history.

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

Administrative grants/delegations/elevations/break-glass authority are checked at operation time against authoritative state, semantic time validity, governed actor Identity state, typed scope, current Role permissions, policy and required authentication assurance. An authenticated session or delayed scheduler never extends expired authority.

A direct AdministrativeGrant may explicitly be grantable and/or delegable. Creating privilege-increasing direct authority requires `administration:manage-authorization` plus one current effective grantable direct basis that contains the complete target permission set, scope, validity and propagated grant/delegation rights. The first slice does not union multiple bases.

AdministrativeDelegation is single-hop in the first slice. It references one direct source AdministrativeGrant, uses the same AdministrativeRole, may only narrow scope/time and is finite. Delegation stops being effective immediately when the source grant is revoked/expired/no longer delegable or no longer contains the delegated authority. Delegated authority cannot itself be delegated.

Scope containment fails closed: GLOBAL may contain supported narrower scopes; SPECIFIC_RESOURCE and CANONICAL_ATTRIBUTE_CLASSIFICATION use exact containment; other modeled scope types require explicit owning-capability containment semantics before they may participate in grant/delegation creation.

AdministrativeRole permission addition is a privilege increase for every current grant/delegation referencing that Role. The prospective edit must satisfy the actor's grantability ceiling for every affected authority; otherwise the whole edit is denied. Permission removal is authority reduction and does not require the privilege-increase ceiling check.

AdministrativeElevation is an explicit finite Administration process/authority source rather than a long-lived grant shortcut. Its implemented process states are `REQUESTED -> PENDING_APPROVAL -> ACTIVE`, with `DENIED`, `CANCELLED`, and `REVOKED` terminal exits. Governance ApprovalCase, when required, is immutable approval evidence only. The typed approval adapter rejects both beneficiary and initiator as approvers, and an unconfigured approver resolver fails closed. Final application revalidates current beneficiary/initiator eligibility, direct grantable basis, basis and target Role permission/revision context, scope/time containment and the bound approval case/fingerprint. Only ACTIVE plus semantic time validity authorizes; stale approval/context cannot authorize current elevation and scheduler delay cannot extend it.

AdministrativeBreakGlassOperation is distinct from ordinary elevation and GLOBAL grants. Effective emergency authority requires short finite validity, strong provider-neutral authentication assurance, reason/incident evidence and current governed actor eligibility. Notification and post-use review are durable obligations but do not extend authority; expiry/revocation takes effect semantically before asynchronous cleanup/review work.

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


## Administrative break-glass invariants

AdministrativeBreakGlassOperation starts ACTIVE only after all activation invariants succeed and may transition explicitly to REVOKED. Expiry is semantic from `validUntil`; a scheduler is not required to make expired authority ineffective.

Invariant set:
- the governed actor is the emergency authority beneficiary in the first slice;
- validity is finite, positive and bounded by the configured break-glass policy;
- non-blank reason and incident/reference evidence are mandatory;
- activation and use require current provider-neutral STRONG assurance within the configured maximum age;
- the default/unconfigured policy fails closed;
- a generic GLOBAL grant or ordinary elevation never substitutes for break-glass;
- activation creates exactly one SECURITY_NOTIFICATION and one POST_USE_REVIEW obligation;
- obligation state cannot extend or restore emergency authority;
- explicit revocation is optimistic-revision guarded and ends authority immediately;
- Audit failure never rewrites a committed Administration result.


## Audit retention, integrity and SIEM invariants

- AuditRecord remains append-only by default; arbitrary UPDATE/DELETE is rejected in PostgreSQL.
- Destructive AuditRecord deletion requires a RUNNING AuditPurgeOperation fenced in the same transaction and is permitted only for exact rows proven archived, old enough under the pinned policy and not protected by any ACTIVE matching legal hold.
- Purge requester and approver are different governed Identities; approval does not bypass execution-time revalidation.
- A verified archive artifact and archived-record query index are evidence/query state, never business authority.
- Public AuditRecord detail/search remains deterministic across online and purged-but-archived records; the online row wins while overlap exists.
- Audit material snapshot is explanatory only. Stable IDs and owning-capability evidence remain the references of record.
- Audit integrity metadata is deterministic tamper-detection metadata, not authorization, ordering, consensus or a global hash chain.
- EvidenceSnapshot is immutable and cannot authorize mutation of current capability state.
- Audit/SIEM delivery is at-least-once. SIEM scheduling or delivery failure cannot roll back or reinterpret an AuditRecord or the business action it describes.
