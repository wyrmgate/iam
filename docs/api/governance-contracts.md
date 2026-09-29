# Governance Contracts

## Purpose

This document defines the first public Governance control-plane contract for
self-service AccessRequest submission and reusable approval work.

Machine-readable contract:

- `apps/server/src/main/resources/contracts/openapi/governance-v1.json`

Base path: `/api/v1/governance`.

## Authentication and actor identity

The API consumes the trusted governed Identity resolved by the control-plane
authentication boundary. A caller does not supply `requesterIdentityId`.

The first public request slice is intentionally self-service only:
`beneficiaryIdentityId` must equal the authenticated governed Identity. On-behalf
requesting for reports or delegated administration is deferred until an explicit
manager/delegation authorization rule exists; accepting an arbitrary beneficiary ID
would not itself establish authority.

Approval authority is separate from IAM administrative permissions. A decision is
accepted only when the authenticated governed Identity is an immutable resolved
approver for the current ApprovalStage. ADR-0016 self-approval denial remains in force.

## AccessRequest API

Resources and operations:

- `POST /access-requests` — create a DRAFT request;
- `GET /access-requests/{requestId}` — read the request and its RequestItems;
- `POST /access-requests/{requestId}/submit` — submit a DRAFT request;
- `GET /request-items/{itemId}` — read one item status.

Creation requires a causal `Idempotency-Key`. The request body carries beneficiary
and one or more typed item specifications. Each item carries exactly one Role or
Entitlement target, `ANY` or `SPECIFIC` principal constraint, and optional temporal
validity.

Submit uses strong revision `ETag` / `If-Match` plus causal idempotency.

Request and item reads are limited to request participants. In the initial self-service
slice requester and beneficiary are the same governed Identity.

## Durable eligibility evaluation

Submit does not expose an `evaluate` or arbitrary status mutation endpoint.

The Governance transaction that records each RequestItem as `SUBMITTED` also appends
`governance.request-item-submitted`. A retryable Governance consumer re-reads current
state and invokes the existing eligibility evaluator. Duplicate or stale facts are safe.

Mandatory evaluator unavailability leaves the item `EVALUATING` and the durable work
is retried. The production evaluator uses the active immutable ACCESS_REQUEST
PolicyVersion, current Role expansion, original sibling RequestItem intent and a bounded
Access-owned current-EffectiveAccess query. Completed RiskAssessment, SoDConflict and
PolicyEvaluation records are immutable evidence. Successful evaluation may produce
`DENIED`, `AUTHORIZED`, or `PENDING_APPROVAL`.

A final approval is revalidated before authorization. Current DENY wins, unavailable
evaluation returns the item to durable `EVALUATING` retry, and a changed approval-plan
fingerprint creates a successor ApprovalCase. Effective approved GovernanceExceptions are
also re-read during this evaluation by exact Identity + SoDRule scope; they may waive that
rule's enforcement action while immutable SoD/risk evidence remains. Expired, revoked or
old-PolicyVersion exceptions cannot be carried by stale approval evidence. Completed
approval evidence never becomes stale mutation authority. Existing authorized-intent processing then continues the chain
toward Access without the HTTP client coordinating internal workflow steps.

## Reusable approval API

Resources and operations:

- `GET /approval-inbox` — pending current-stage work for the authenticated approver;
- `GET /approvals/{approvalCaseId}` — immutable approval plan/stage/approver/decision evidence;
- `POST /approvals/{approvalCaseId}/approve`;
- `POST /approvals/{approvalCaseId}/reject`.

The inbox is a read model over ApprovalCase, ApprovalPlan, ApprovalStage,
ApprovalApprover and ApprovalDecision. It is not a new authoritative ApprovalTask
aggregate.

Inbox pagination is deterministic by `createdAt + id`. Cursor transport follows
ADR-0012: signed, tenant-bound, approver-Identity-bound, time-bounded and rotation-safe.

Approval decision mutation uses ApprovalCase revision `ETag` / `If-Match` and
causal `Idempotency-Key`. Decision evidence is append-only. Replaying the same
idempotent request returns the current case; a conflicting use of the key is rejected.

Approval evidence may be read only by the case initiator or a snapshotted approver.
Approval completion changes only the subject flow through the reusable result sink;
the approval API never writes Access persistence.

## State meaning

The public API preserves the existing separation:

`SUBMITTED -> EVALUATING -> PENDING_APPROVAL -> AUTHORIZED -> APPLIED`

- `AUTHORIZED`: Governance requirements succeeded.
- `APPLIED`: Access accepted the authoritative AccessAssignment.
- neither state means provider provisioning succeeded.

Provider fulfillment remains downstream through EffectiveAccess, desired state and
Integration provisioning.

## Error and retry semantics

All responses use the normal semantic error envelope with `X-Correlation-Id` and
`Cache-Control: no-store`.

Important codes include:

- `validation_failed`;
- `forbidden`;
- `not_found`;
- `stale_revision`;
- `idempotency_conflict`;
- `idempotency_in_progress`;
- reusable ApprovalCommand semantic codes such as
  `approval_actor_not_approver`, `approval_self_decision_forbidden`,
  `approval_case_not_pending`, and `approval_decision_conflict`.

Provider errors are not Governance API errors and do not roll back approval.


## Access review runtime boundary

The first `IDENTITY_ACCESS` ReviewCampaign runtime is internal in this slice and has no
public HTTP surface yet.

Governance owns ReviewCampaign, ReviewItem, immutable ReviewDecision and
ReviewRemediation. Campaign generation uses the Access-owned semantic
`AccessReviewSnapshotQuery`; Governance never reads Access persistence. The fixed
`snapshotAt` is a creation/validity cutoff and each ReviewItem stores the assignment state
actually read during bounded generation.

`KEEP` creates no remediation. `REVOKE` creates durable remediation work. A separate
worker invokes the Access-owned `AccessReviewRemediationCommand`, which re-reads current
AccessAssignment authority and returns `APPLIED` or `NO_ACTION_REQUIRED` causally
idempotently by ReviewRemediation ID.

ReviewCampaign `COMPLETED` means all generated ReviewItems have immutable decisions. It
does not mean ReviewRemediation or provider fulfillment is complete.

Public campaign create/read/start, reviewer inbox/decision, and remediation-status
operations remain a later OD-003 API slice.
