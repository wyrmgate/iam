# Governance Approval Contracts

## Purpose

This document defines the reusable Governance-owned approval orchestration boundary
introduced by ADR-0016.

Machine-readable participant API:

- `apps/server/src/main/resources/contracts/openapi/governance-v1.json`

The approval capability is reusable across typed IAM flows. AccessRequest is the first
consumer, not the owner of approval semantics.

## Approval ownership

Governance owns:

- `ApprovalCase` — mutable process state for one typed subject;
- `ApprovalPlan` — immutable decision-time plan snapshot;
- `ApprovalStage` — immutable ordered stage;
- `ApprovalParticipant` — immutable resolved governed Identity participant;
- `ApprovalDecision` — append-only decision evidence.

The subject's owning capability remains authoritative for the business subject.

Approval never directly mutates Access, Catalog, Administration, Credential or another
capability's repository. Terminal approval produces a typed internal outcome fact carrying
the captured subject revision. The subject owner re-reads current state and decides whether
the outcome is still applicable.

## Typed subjects

The initial subject type vocabulary is explicit:

- `REQUEST_ITEM`;
- `ADMINISTRATIVE_ELEVATION`;
- `ROLE_VERSION_ACTIVATION`;
- `POLICY_VERSION_ACTIVATION`;
- `GOVERNANCE_EXCEPTION`;
- `CREDENTIAL_OPERATION`.

Only `REQUEST_ITEM` is wired as a consuming business flow in this slice. Listing a future
subject type does not imply its domain workflow is implemented.

There is intentionally no public generic "create approval case" API. Typed subject
resolvers open cases internally with a complete plan snapshot.

## Plan semantics

The initial approval plan is deliberately constrained:

- sequential ordered stages;
- each stage uses `ANY_ONE` or `ALL`;
- participants are resolved governed Identity IDs;
- one to twenty stages;
- one to one hundred unique participants per stage;
- requester self-approval is `DENY_REQUESTER` by default;
- plans, stages and participants are immutable after creation.

`ANY_ONE` completes when any current-stage participant approves.

`ALL` completes only after every current-stage participant approves.

A rejection from any current-stage participant rejects the case immediately.

Arbitrary DAGs, executable scripts, BPMN semantics and generic workflow payloads are not
canonical approval constructs.

## Decision semantics

Only an authenticated Identity resolved into the current stage may decide. Administrative
permission does not grant approval rights.

Decision endpoints:

- `POST /api/v1/approval-cases/{approvalCaseId}/approve`
- `POST /api/v1/approval-cases/{approvalCaseId}/reject`

Decision mutations use:

- strong `ETag: "rev-N"` / `If-Match`;
- durable causal `Idempotency-Key`;
- immutable `ApprovalDecision` evidence;
- operation correlation/causation.

A participant may record at most one decision for one stage/case. Completed or non-current
stages cannot accept new decisions.

## Universal approver inbox

`GET /api/v1/approval-inbox` returns only current actionable work for the authenticated
Identity:

- ApprovalCase is `PENDING`;
- actor is a participant in the current stage;
- actor has not already decided that stage.

The signed continuation cursor is tenant-bound, actor-Identity-bound, resource-bound and
time-bounded under ADR-0012.

This inbox can contain multiple subject types without making ApprovalCase the owner of those
subjects.

## AccessRequest first consumer

`AccessRequest` owns one beneficiary and scalable `RequestItem` rows.

RequestItem lifecycle begins:

```text
DRAFT -> SUBMITTED -> EVALUATING -> PENDING_APPROVAL
                                      |-> REJECTED
                                      |-> AUTHORIZED -> APPLIED
                         |-> DENIED
```

Unfinished items may later become CANCELLED or EXPIRED through subject-specific commands.

Eligibility and approval-plan resolution happen before approval. The production baseline in
this slice deliberately installs a fail-closed evaluator: if mandatory governance
evaluation/approver resolution is unavailable, the RequestItem remains `EVALUATING`; no
ApprovalCase or AccessAssignment is invented.

When a RequestItem is eligible, its resolver supplies an immutable ApprovalPlanSpec. The
ApprovalCase captures the RequestItem revision that will be current at
`PENDING_APPROVAL`.

A terminal RequestItem approval outcome is accepted only when:

- the ApprovalCase ID still matches the RequestItem;
- the captured subject revision still equals the RequestItem revision;
- the item remains `PENDING_APPROVAL`.

A stale outcome is a no-op and cannot authorize changed intent.

## Governance -> Access handoff

Approval changes RequestItem to `AUTHORIZED`; it does not create provider state.

The durable approval-outcome worker then invokes the Access-owned `AccessIntentCommand`.
Access alone creates the authoritative AccessAssignment.

Request-derived assignments use:

- `provenanceKind = APPROVED_REQUEST`;
- `provenanceRefId = requestItemId`.

That provenance is unique per tenant, making the cross-capability apply operation
idempotent across crash/retry.

Only after Access accepts the semantic intent does Governance move the RequestItem to
`APPLIED`.

`APPLIED` still does not mean provider provisioning succeeded. EffectiveAccess, desired
state, Integration fulfillment and provider observation remain separate dimensions.

## Outcome facts

Terminal cases emit a data-minimized internal fact whose type is subject-specific:

`governance.approval-outcome.<typed-subject>`

For RequestItem:

`governance.approval-outcome.request-item`

Payload contains only:

- ApprovalCase ID;
- subject type;
- subject ID;
- captured subject revision;
- APPROVED or REJECTED outcome.

Subject-specific event types prevent unrelated capability consumers from competing for a
single shared outbox record.

## Deferred approval capabilities

The following remain typed extensions rather than hidden generic workflow behavior:

- dynamic approver rule authoring;
- approval delegation;
- reminders/deadlines;
- escalation;
- assurance requirements;
- plan supersession due to policy/risk revision changes;
- approval comments/attachments;
- bulk decisions;
- UI configuration of reusable policy templates.

These additions must preserve immutable decision evidence and subject ownership.
