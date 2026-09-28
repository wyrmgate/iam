# ADR-0016: Reusable typed approval orchestration within Governance

Status: Accepted

## Context

Wyrmgate needs approvals in more than one business flow: access requests, administrative elevation, high-risk catalog/policy activation, governance exceptions, and potentially other sensitive changes.

Making each flow implement an unrelated approval engine would duplicate decision semantics and evidence. Making a generic BPM/workflow engine authoritative would violate ADR-0009 by moving business meaning into generic orchestration infrastructure.

The existing formal model already requires immutable ApprovalPlans and ApprovalDecisions, constrained sequential stages, ANY_ONE / ALL stage decision modes, and fail-closed governance behavior for privilege increases.

## Decision

Governance owns a reusable **typed approval orchestration** capability.

The reusable approval model consists of:

- **ApprovalCase** — mutable authoritative approval-process state for one typed subject reference;
- **ApprovalPlan** — immutable decision-time snapshot attached to one case;
- **ApprovalStage** — immutable ordered stage with decision mode `ANY_ONE` or `ALL`;
- **ApprovalApprover** — immutable resolved governed-Identity snapshot for one stage;
- **ApprovalDecision** — append-only approval/rejection evidence.

Approval subjects are expressed as a strongly typed `ApprovalSubjectKind` plus stable subject ID. They are not arbitrary JSON workflow payloads. New subject kinds require an explicit governed contract/code change.

Approval orchestration owns only approval state. The capability that owns the approved business object keeps its own business lifecycle and decides what an approved result authorizes. Approval never mutates another capability's repository/table directly.

The initial execution model is intentionally constrained:

1. stages execute sequentially by ordinal;
2. `ANY_ONE` completes a stage after one approval;
3. `ALL` completes a stage only after all snapshotted approvers approve;
4. any valid rejection rejects the case;
5. only a snapshotted approver for the current stage may decide;
6. self-approval by the case initiator/requester is denied by default;
7. decisions are immutable; a repeated identical decision is idempotent while a conflicting repeat is rejected;
8. the case uses optimistic revision for mutable process state;
9. plans/stages/approver membership are immutable after creation.

The reusable contract exposes semantic approval commands/results. It does not expose arbitrary state mutation.

Technical scheduling may later deliver reminders, deadlines, or escalation triggers, but Governance owns the semantic deadline/escalation policy. Platform scheduling owns only delivery mechanics.

## First consumer

Access request Governance is the first consumer.

A RequestItem may create an ApprovalCase with subject kind `ACCESS_REQUEST_ITEM`. Approval result advances the RequestItem's Governance state, but `AUTHORIZED` remains separate from Access applying the intent and from provider fulfillment.

The approval persistence model therefore contains no AccessRequest-specific target, entitlement, role, beneficiary, or provisioning fields.

## Consequences

- Approval behavior can be reused by later administrative-elevation, exception, role/policy activation, and other governed flows.
- Calling capabilities do not duplicate approval evidence/state semantics.
- Approval remains portable and framework-neutral.
- Reuse does not introduce a universal workflow aggregate, arbitrary DAG, scripting engine, or generic JSON process model.
- Approval plans can evolve by creating a replacement plan/case revision while preserving immutable historical evidence.
- A future workflow/BPM product may act as an adapter for human-task delivery/visualization, but cannot become approval authority.

## Deferred

This ADR does not yet define:

- delegation;
- reminders;
- deadlines/escalation;
- dynamic approver rule resolution;
- parallel stage DAGs;
- weighted/quorum voting beyond ANY_ONE/ALL;
- public approval inbox APIs;
- maker-checker policy for every future subject kind.

Those are later typed extensions if required.
