# Workflow and Process Orchestration Architecture

## Core rule

Business state belongs to the capability that owns the business concept. A workflow engine, scheduler or queue may coordinate delivery and time, but it must not become a second source of truth for request, approval, review, credential, access or integration state.

This architecture separates:

- **domain state machines** — authoritative business state and invariants;
- **domain process/orchestration objects** — durable long-running business processes owned by a capability;
- **technical work scheduling** — generic timers, retries, leasing and command delivery;
- **external/provider execution** — Integration-owned work with explicit attempts and observed results.

## Decision: no general-purpose BPM engine in the core

The initial IAM v2 implementation should not depend on a generic BPMN/workflow engine for domain semantics. Approval, review, provisioning, reconciliation, credential rotation, merge/split and lifecycle orchestration have materially different invariants and evidence requirements.

A generic workflow engine may be added later as an adapter for selected operational process definitions, but canonical business state remains in domain-owned aggregates/process records.

## Orchestration ownership

| Process | Owning capability | Durable authoritative/process objects |
| --- | --- | --- |
| access request / approval | Governance | AccessRequest, RequestItem, ApprovalPlan, ApprovalDecision |
| access review | Governance | ReviewCampaign, ReviewItem, ReviewDecision, ReviewRemediation |
| identity merge/split | Identity | IdentityMergeOperation / IdentitySplitOperation |
| credential rotation | Credential | CredentialRotation |
| provisioning | Integration | ProvisioningJob, ProvisioningTask, ProvisioningAttempt |
| reconciliation | Integration | ReconciliationRun and observation staging/checkpoint state |
| source import | Identity + Integration adapter boundary | SourceImportRun / staging observations / correlation work |
| application or target retirement | Catalog coordinating semantic commands | retirement operation/process record when cross-capability work is required |
| tenant decommissioning | Platform/Administration coordination | explicit durable decommissioning operation |

A process that crosses capabilities coordinates through semantic commands and facts. It does not directly mutate another capability's repository.

## Domain state machine versus process state

A domain object's state answers what IAM has decided about that object. A process object's state answers where a long-running operation is in its execution.

Examples:

```text
AccessAssignment = REVOKED
ProvisioningTask = FAILED_RETRYABLE
ObservedGrant = PRESENT
```

and:

```text
Credential = ACTIVE
CredentialRotation = VERIFYING
```

The process state never replaces the authoritative domain state.

## Durable process record

Long-running business processes should expose a common conceptual envelope without requiring a universal base entity:

```text
Process identity
Tenant/isolation context
Process type
Business subject/reference
State
Revision
StartedAt / UpdatedAt / CompletedAt
Actor / initiator
CorrelationId
Current checkpoint/phase
Failure summary
Next action time when applicable
```

Each process owns typed process-specific state. Avoid one generic `Map<String,Object>` process payload as the canonical model.

## Timer semantics

Time validity is semantic and does not depend on scheduler punctuality. Schedulers materialize work that is already due; they do not extend validity.

Examples:

- an AccessAssignment is no longer effective after `validUntil` even if the expiry materializer is delayed;
- an AdministrativeGrant is ineffective after its validity window;
- a GovernanceException no longer covers a violation after expiry;
- an approval deadline may be considered expired from time comparison before its timeout worker runs.

Timers should therefore carry a due time and semantic command, for example:

```text
ExpireApprovalPlan(planId, expectedRevision)
StartReviewCampaign(campaignId)
ReevaluateScheduledAssignment(assignmentId)
StartCredentialRotation(rotationId)
RunReconciliation(bindingId, scheduleRevision)
```

A timer firing late must re-read current state and decide whether work is still applicable.

## Scheduler architecture

The scheduler is generic technical infrastructure. It owns scheduling/claiming metadata, not domain lifecycle state.

A scheduled-work record may contain:

```text
scheduleId
handlerType
subjectReference
dueAt
attempt
leaseUntil
idempotencyKey
payloadVersion
```

The handler converts the scheduled trigger into a semantic application command. Direct SQL mutation of domain state from scheduler jobs is forbidden.

Multiple scheduler instances may claim due work using leases/locking. Duplicate delivery is expected and handlers must be idempotent/revision-aware.

## Retry model

Retries are classified by failure semantics.

### Retryable technical failure

Examples: provider timeout, rate limit, transient network error, temporary database/message transport problem.

Use bounded backoff with jitter and explicit attempt evidence. Preserve the original business decision.

### Permanent/semantic failure

Examples: target account permanently invalid, unsupported operation, required mapping missing, authorization no longer valid, dependency retired.

Do not retry forever. Transition the process/task to a terminal failure or `MANUAL_REQUIRED` state and create a finding/notification where appropriate.

### Stale work

If current desired/business state no longer requires the work, mark it `SUPERSEDED`, `SKIPPED` or equivalent. Stale asynchronous work is not a failure.

## Retry policy is process-specific

A generic retry service may calculate delays, but policy is owned by the process/adapter context. Provisioning retries, notification retries, approval reminders and reconciliation retries are not one semantic policy.

Retry records should retain:

- attempt number;
- normalized failure category/code;
- next eligible attempt time;
- provider retry-after where available;
- last failure evidence;
- terminal/manual-required reason.

## Escalation

Escalation is a governance decision, not merely a scheduler retry.

Approval/review escalation must be represented in the immutable plan/configuration that governs the workflow. Examples include:

- remind current approver after a threshold;
- add or replace with manager/owner/escalation group;
- escalate to security/governance role;
- expire/reject/pending-manual after deadline.

Timeout must never imply auto-approval by default.

Materially changing approval participants or approval requirements creates/supersedes a plan rather than mutating historical evidence.

## Approval orchestration

ApprovalPlan is an immutable snapshot of required decisions for one RequestItem. Initial supported structures remain intentionally constrained:

- sequential stages;
- stage decision mode `ANY_ONE` or `ALL`;
- typed approver-resolution rules;
- reminders, deadlines and escalation policy.

Do not introduce arbitrary BPM DAGs initially.

At each important transition, revalidate material dependencies such as identity lifecycle, requested access eligibility, RoleVersion, policy, risk and SoD context. Material change may supersede the plan and return the item to evaluation.

ApprovalDecision remains append-only evidence.

## Review orchestration

ReviewCampaign coordinates generation and campaign timing. ReviewItem is the scalable reviewer work boundary.

Campaign completion means required reviewer decisions are complete according to campaign rules. It does not mean all technical remediation is complete.

ReviewRemediation reads current authoritative state before action. If access was already changed elsewhere, remediation may complete as `NO_ACTION_REQUIRED` rather than replaying the historical snapshot.

Escalations/reminders apply to undecided ReviewItems; they do not mutate earlier ReviewDecisions.

## Provisioning orchestration

ProvisioningJob groups one causal desired-state realization. ProvisioningTask contains provider-neutral operations and may have an acyclic same-job dependency graph.

Task execution flow:

```text
PENDING -> READY -> RUNNING -> SUCCEEDED
                    |-> FAILED_RETRYABLE -> READY
                    |-> FAILED_FINAL
                    |-> MANUAL_REQUIRED
PENDING/READY -> BLOCKED | SUPERSEDED | SKIPPED
```

Before each external attempt, Integration revalidates current desired state/revision. External calls happen outside the transaction that committed authoritative business state.

Each external attempt creates immutable ProvisioningAttempt evidence.

## Credential rotation orchestration

Credential lifecycle state and rotation process state remain separate.

Recommended rotation process:

```text
PLANNED
 -> CREATING_REPLACEMENT
 -> DISTRIBUTING
 -> VERIFYING
 -> CUTOVER_COMPLETE
 -> REVOKING_OLD
 -> COMPLETED
```

Failure exits include `FAILED`, `MANUAL_REQUIRED` and `FAILED_REMEDIATION` where appropriate.

The old credential remains usable only according to credential/security policy, not because the rotation process happens to be incomplete. Compromise handling may intentionally choose service interruption over continued use of known-compromised material.

## Identity merge/split orchestration

Merge and split are durable Identity-owned operations because they may span links, principals, assignments, ownership, administrative grants, governance references and projections.

The operation proceeds through explicit validation/planning/application phases. Historical records are not rewritten as though the survivor identity always owned past decisions.

Cross-capability changes are issued as semantic commands and recorded independently. Partial progress must be recoverable and auditable.

## Retirement/decommissioning orchestration

Cross-capability retirement is coordinated, not cascaded.

Example ApplicationTarget retirement:

```text
Catalog marks target RETIRING
 -> new access/provisioning blocked according to policy
 -> Governance/Access evaluates existing assignments
 -> Integration realizes removal/disable work
 -> reconciliation proves/records residue
 -> unresolved residue becomes findings/manual work
 -> Catalog retires when configured exit criteria are met
```

No database cascade delete is used as business orchestration.

## Compensation versus rollback

Long-running distributed processes generally use **forward recovery/compensation**, not distributed transaction rollback.

A valid upstream governance decision is not rolled back merely because a downstream provider failed. Compensation is a new explicit business/technical action with its own evidence.

Examples:

- failed grant provisioning -> retry/manual remediation; do not unapprove the request;
- failed revoke -> assignment remains revoked; observed residual access becomes drift/finding;
- rotation failure after replacement creation -> explicitly clean up/revoke replacement where safe; do not erase attempts.

## Process cancellation

Cancellation is semantic and process-specific.

A process may be cancellable only while doing so does not contradict an already committed business decision or unsafe external state. Cancellation never means deleting history.

Examples:

- a draft/scheduled request process can be cancelled;
- a running provisioning task that already sent a provider request may require reconciliation rather than pretending the call never occurred;
- a credential rotation after cutover may require forward completion/cleanup instead of cancellation.

## Exactly-once assumptions

Do not depend on exactly-once timer, message or external-call delivery.

Use:

- idempotency keys for the same causal operation;
- aggregate/process revision checks;
- inbox/dedup where side effects require it;
- provider idempotency tokens where supported;
- reconciliation to establish eventual external truth.

## Process observability

Every long-running process should be queryable by business reference/correlation and expose:

- current process state;
- authoritative business state separately;
- current/next technical action;
- attempt/failure summary;
- timestamps and deadlines;
- correlation/causation identifiers;
- manual remediation requirements;
- links to relevant immutable evidence.

Operator retry/skip/supersede actions are themselves authorized, reasoned and audited operations.

## Workflow engine boundary

A future workflow/BPM engine is acceptable only as an adapter when it provides operational value such as durable timers, human task routing or visualization.

It must not:

- own canonical RequestItem/ReviewItem/Assignment/Credential state;
- mutate capability repositories directly;
- encode IAM authorization/risk/SoD semantics only inside workflow scripts;
- make workflow-engine instance IDs the primary domain identity;
- require arbitrary scripting to express core domain rules.

The IAM application must remain able to reconstruct authoritative process/business state from its own durable records and evidence.

## Initial implementation recommendation

Start with:

- PostgreSQL-backed durable process/task/timer records;
- transactional outbox for facts/work publication;
- workers using leases or `FOR UPDATE SKIP LOCKED`-style claiming where appropriate;
- bounded retry policies with jitter;
- semantic application handlers that re-read and revalidate state;
- no mandatory external BPM engine.

This architecture can later map timers/work to Kafka, SQS, Temporal, Camunda or another platform without redefining canonical domain/process semantics.
