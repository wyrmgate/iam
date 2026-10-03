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

Public AccessRequest create/submit and Approval approve/reject attempts append
data-minimized ADR-0031 AuditRecord evidence only after the Governance transaction commits
or rolls back. Audit retains the governed actor, semantic action, target AccessRequest or
ApprovalCase when known, normalized SUCCESS/DENIED/FAILURE outcome and request correlation
ID. Beneficiary and RequestItem targets, approval reason, plan/stage/approver lists,
policy/risk/SoD evidence, request payloads and exception detail are not copied into
AuditRecord. Audit persistence failure is operationally logged and never rewrites the
Governance business outcome.

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


## Access review / certification API

ADR-0019 `IDENTITY_ACCESS` ReviewCampaign is now exposed through the public Governance
control-plane API without changing review ownership or lifecycle semantics.

Campaign administration operations:

- `POST /review-campaigns` — create one typed DRAFT campaign with explicit subject,
  reviewer and `snapshotAt`;
- `GET /review-campaigns` — deterministic administratively authorized collection page;
- `GET /review-campaigns/{campaignId}` — read campaign detail;
- `POST /review-campaigns/{campaignId}/start` — explicit DRAFT -> GENERATING operation;
- `GET /review-campaigns/{campaignId}/items` — bounded deterministic ReviewItem page.

Campaign create and start use causal `Idempotency-Key`; start also requires revision
`ETag` / `If-Match`. Stable Administration permissions are
`review-campaign:create`, `review-campaign:read` and `review-campaign:start`.
Collection enumeration requires collection/global read authority; a
resource-specific campaign grant does not grant enumeration.

Reviewer operations:

- `GET /review-inbox` — pending ReviewItems assigned to the authenticated governed
  Identity;
- `GET /review-items/{reviewItemId}` — immutable snapshot plus decision/remediation
  evidence;
- `POST /review-items/{reviewItemId}/keep`;
- `POST /review-items/{reviewItemId}/revoke`;
- `GET /review-remediations/{remediationId}`.

Reviewer decision authority comes from the immutable ReviewItem reviewer assignment,
not from a generic administrative permission. An administrator who is not the assigned
reviewer cannot KEEP or REVOKE an item. Decision mutations require ReviewItem
`If-Match` plus causal idempotency.

Public ReviewCampaign create/start and ReviewItem KEEP/REVOKE attempts append
data-minimized ADR-0031 AuditRecord evidence only after the Governance review transaction
commits or rolls back. Audit retains the governed actor, semantic action, target campaign
or item when known, normalized SUCCESS/DENIED/FAILURE outcome and request correlation ID.
Subject/reviewer Identity IDs, snapshot content, review reason, AccessAssignment/remediation
detail, request payloads and exception detail are not copied into AuditRecord. Audit
persistence failure is operationally logged and never rewrites the review business outcome.

Specific campaign/item/remediation reads are allowed to the assigned reviewer or to an
administrator with `review-campaign:read` authority for that campaign. The subject
Identity does not gain visibility merely by being the review subject.

Campaign, per-campaign item and reviewer-inbox paging use ADR-0012 signed cursors bound
to tenant plus the relevant campaign/reviewer context. V34 adds only the indexes needed
for those bounded query paths; it adds no new review business state.

Governance still owns ReviewCampaign, ReviewItem, immutable ReviewDecision and
ReviewRemediation. Campaign generation continues to use the Access-owned semantic
`AccessReviewSnapshotQuery`; Governance never reads Access persistence. `KEEP` creates
no remediation. `REVOKE` creates durable remediation work whose Access command re-reads
current authoritative AccessAssignment state.

ReviewCampaign `COMPLETED` still means every generated ReviewItem has an immutable
decision. ReviewRemediation state and provider fulfillment remain separate dimensions;
the public API provides no generic remediation-status mutation operation.


## Governance policy administration

The Governance v1 contract now exposes the implemented ADR-0017 ACCESS_REQUEST PolicyVersion lifecycle as semantic policy operations:

- `POST /policies/access-request/versions` — create typed DRAFT content;
- `GET /policies/access-request/versions/{policyVersionId}`;
- `GET /policies/access-request/active`;
- `POST /policies/access-request/versions/{policyVersionId}:ready`;
- `POST /policies/access-request/versions/{policyVersionId}:activate`;
- `POST /policies/access-request/versions/{policyVersionId}:cancel`.

The public contract names ACCESS_REQUEST explicitly because no generic policy-kind/expression engine exists. DRAFT content carries the existing typed Entitlement-pair SoD rules and constrained sequential approval-plan stages only. READY/ACTIVE/CANCEL transitions use strong revision ETags plus causal idempotency. Activated/superseded content remains immutable.

Stable Administration permissions are `governance-policy:read|create|ready|activate|cancel`; they are default-deny and are not silently granted to INITIAL_TENANT_ADMIN.

## GovernanceException API

The ADR-0018 exception lifecycle is now public through:

- `POST /exceptions` — request one typed IDENTITY_SOD_RULE exception and start its reusable ApprovalCase;
- `GET /exceptions/{governanceExceptionId}`;
- `POST /exceptions/{governanceExceptionId}:revoke`.

The authenticated governed actor is always the requester; clients cannot assert a different requester identity. The request binds one subject Identity to one exact SoDRule of the current ACTIVE PolicyVersion, explicit validity, business reason, an immutable typed approval plan and optional predecessor for renewal. Approval/rejection still uses the reusable Approval API and remains evidence rather than Access authority.

Revoke is an explicit authority-reduction operation with If-Match and causal idempotency. Semantic expiration remains clock-based even if scheduler materialization is late. Exception mutation never writes Access persistence.

Permissions are `governance-exception:read|create|revoke`, default-deny and outside INITIAL_TENANT_ADMIN. Audit outcome evidence is data-minimized; business reason, approval-plan detail and SoD context are not copied into AuditRecord.
