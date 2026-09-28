# Governance Contracts

## Purpose

This document defines the first public Governance control-plane contract for
AccessRequest/RequestItem and the reusable ApprovalCase mechanism introduced by ADR-0016.

Machine-readable contract:

- `apps/server/src/main/resources/contracts/openapi/governance-v1.json`

Base path: `/api/v1`.

## AccessRequest authority and visibility

AccessRequest and RequestItem are Governance-owned authoritative process state.

Public creation always derives `requesterIdentityId` from the trusted authenticated
governed actor. A caller cannot supply or spoof requester identity.

Self-service creation for the authenticated Identity requires no platform-administrator
grant. Creating a request whose beneficiary is another Identity requires
`access-request:request-for-others`. The beneficiary must exist in the same tenant.

Request detail and RequestItem detail are readable by:

- the request requester;
- the request beneficiary; or
- an actor with `access-request:read` for that specific AccessRequest.

There is no public request collection search in this slice.

## Request operations

Resources:

- `POST /access-requests`
- `GET /access-requests/{requestId}`
- `GET /request-items/{itemId}`

Explicit operation:

- `POST /access-requests/{requestId}/submit`

Create requires `Idempotency-Key`. Submit requires both `If-Match` and
`Idempotency-Key`.

A submit request does not execute eligibility/policy evaluation synchronously. The
transaction that moves each item to `SUBMITTED` also appends one internal
`governance.request-item-submitted` fact. A separate retryable Governance consumer
re-reads current item state and invokes the existing eligibility/approval domain flow.

If mandatory evaluation is unavailable, the item remains `EVALUATING` and the outbox
work is retried. Duplicate, replayed or stale delivery cannot bypass the RequestItem state
machine.

## RequestItem status

The public RequestItem resource exposes the typed requested access semantics and current
Governance state, including:

- Role or Entitlement target;
- ANY/SPECIFIC principal constraint;
- optional specific Principal;
- optional validity window;
- current RequestItem state;
- ApprovalCase ID when approval is required;
- resulting AccessAssignment ID after application;
- evaluation code and optimistic revision.

`AUTHORIZED`, `APPLIED` and provider fulfillment remain distinct:

`AUTHORIZED -> AccessIntentCommand -> AccessAssignment -> APPLIED -> EffectiveAccess -> DesiredState -> Integration`

## Reusable Approval API

Resources:

- `GET /approval-inbox`
- `GET /approval-cases/{caseId}`

Explicit decision operations:

- `POST /approval-cases/{caseId}/approve`
- `POST /approval-cases/{caseId}/reject`

The inbox is not an administrative search endpoint. It is bound to the authenticated
Identity and returns only PENDING cases where that Identity is a snapshotted approver for
the current stage and has not already decided in that stage.

Inbox pagination is deterministic by `createdAt + id` and uses an integrity-protected,
tenant-bound, actor-bound, time-bounded cursor under ADR-0012.

Approval evidence reads are limited to the case initiator or an Identity snapshotted as an
approver participant in the immutable plan. Unauthorized callers receive no evidence
through this API.

Decision authority comes from the reusable ApprovalCase domain contract, not from a
generic IAM administrative permission. Only a resolved approver for the current stage may
decide; self-approval remains denied by default.

Approve/reject require `If-Match` and `Idempotency-Key`. The idempotency record and
approval mutation commit in the same database transaction, so HTTP retry cannot cause a
second decision or skip the approval state machine.

## Evidence and state semantics

ApprovalPlan, ApprovalStage, resolved ApprovalApprover snapshots and ApprovalDecision are
immutable evidence. ApprovalCase is the mutable process state and therefore exposes an
ETag revision.

An approved ApprovalCase does not itself mean provider access exists. For AccessRequest
items it advances the item to `AUTHORIZED`; the separately durable Governance-to-Access
handoff later advances the item to `APPLIED`.

## Error and security semantics

Responses use the standard stable error envelope with `code`, `message`,
`correlationId` and optional field errors.

Relevant codes include:

- `validation_failed`;
- `forbidden`;
- `not_found`;
- `stale_revision`;
- `idempotency_conflict`;
- `idempotency_in_progress`;
- existing stable AccessRequest and Approval domain error codes.

Tenant isolation is enforced before normal resource visibility. Ordinary API responses do
not expose provider-native secrets or credential material.

## Explicitly deferred

This first public slice does not add:

- AccessRequest collection search/reporting;
- cancellation/expiry operations;
- approval delegation, reminders, deadlines or escalation;
- dynamic approver-rule resolution;
- new SoD/risk/policy implementation beyond the existing evaluator port;
- public provider fulfillment controls.
