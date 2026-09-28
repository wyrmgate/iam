# ADR-0016: Reusable Governance-owned approval orchestration

Status: Accepted

## Context

The v0.2 formal specification describes ApprovalPlan and ApprovalDecision primarily in the
AccessRequest / RequestItem workflow. Wyrmgate also has other operations that can require
human approval: administrative elevation, high-risk catalog activation, governance
exceptions, credential/security operations, and future explicitly governed actions.

Embedding a separate approval state machine inside every subject would duplicate approval
semantics and evidence. Making approval a general BPM/workflow engine would create the
opposite problem: business state would leak into a generic orchestration layer and the
workflow engine could become a second source of truth.

## Decision

Governance owns a reusable, typed approval orchestration capability consisting of:

- `ApprovalCase` — one approval process instance for one semantic business subject;
- `ApprovalPlan` — immutable decision-time snapshot of required ordered stages;
- `ApprovalStage` — immutable ordered stage using a constrained decision mode;
- `ApprovalParticipant` — immutable resolved governed Identity participant;
- `ApprovalDecision` — append-only decision evidence.

Approval is reusable across subject types, but it is not universal business-state
ownership. The subject's owning capability remains authoritative for the subject and must
apply or reject the approval outcome through its own semantic command/state transition.

### Typed subject reference

An ApprovalCase references:

- subject type;
- stable subject ID;
- subject revision captured when the case was opened;
- requester/initiator Identity when applicable;
- correlation/causation context.

Initial subject types are explicitly enumerated rather than arbitrary strings. New subject
types are added through reviewed typed contracts.

No arbitrary subject payload or executable script is stored as canonical approval state.

### Plan shape

The initial plan model is intentionally constrained:

- ordered sequential stages;
- stage decision mode `ANY_ONE` or `ALL`;
- each stage contains one or more resolved governed Identity participants;
- requester self-approval is denied by default and may only be enabled by an explicit typed
  plan policy;
- plans and participants are immutable after activation;
- materially changing required approvers or stage rules creates a new plan and supersedes
  the prior case/plan rather than rewriting decision history.

Arbitrary DAGs, scripting, BPMN semantics and generic expression languages are not part of
the canonical approval model.

### Decision semantics

Only a current-stage participant may decide.

- `APPROVE` records immutable evidence.
- `REJECT` records immutable evidence and rejects the case.
- In `ANY_ONE`, one approval completes the stage.
- In `ALL`, every participant must approve.
- Duplicate delivery of the same causal decision must be idempotent.
- A participant cannot record contradictory decisions in the same stage/case.
- Decisions from non-current or completed stages are rejected.

When a stage completes, the case advances to the next stage. When the final stage completes,
the case becomes `APPROVED`. A rejection makes the case `REJECTED`.

### Outcome boundary

Terminal approval state produces a data-minimized Governance internal fact containing the
ApprovalCase ID, typed subject reference, captured subject revision, outcome and causal
context.

Approval does not directly mutate another capability's authoritative tables.

The subject owner consumes or is invoked through a semantic command, re-reads current
subject state/revision and decides whether the outcome is still applicable. A stale outcome
must not authorize a changed subject. Re-evaluation may open a successor ApprovalCase.

### Concurrency, retries and time

ApprovalCase is mutable authoritative Governance state and uses optimistic revision.

ApprovalDecision is immutable evidence.

External retryable approval decisions use causal idempotency. At-least-once delivery is
assumed.

Deadline, reminder, escalation and delegation behavior may be added as typed plan semantics
and scheduler handlers. Generic scheduler infrastructure owns only timer/delivery mechanics,
not approval meaning.

### Authorization

Control-plane authorization to administer approval configuration is separate from the
business right to decide an ApprovalCase.

A decision actor must:

1. be an authenticated governed Identity;
2. be a resolved participant in the current stage;
3. satisfy any later assurance/delegation policy;
4. not violate the plan's self-approval rule.

Administrative permission alone does not make an actor an approver.

## Consequences

- AccessRequest is the first consumer but does not own approval stage/decision persistence.
- Future approval-requiring flows reuse the same constrained engine without sharing their
  business state.
- Approval inbox/read models may span multiple typed subject kinds.
- Business capabilities remain able to evolve their own state machines independently.
- Approval evidence remains stable and understandable after the subject changes.
- The initial implementation stays compatible with a modular monolith and may later use a
  workflow/timer product only as an adapter.
- This ADR amends v0.2 wording that scopes ApprovalPlan directly to RequestItem. The next
  formal specification revision must fold this reusable subject boundary into DDD, SAD,
  Security and RTM artifacts.
