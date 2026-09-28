# ADR-0016: Reusable Governance approval orchestration with consumer-owned business state

Status: Accepted

## Context

Multiple IAM flows require human approval: access requests, selected RoleVersion activations,
GovernanceException decisions, administrative elevation, and security/credential actions.

Embedding a separate approval state machine in every flow would duplicate decision semantics,
inbox behavior, evidence rules, deadlines and escalation mechanics. Moving those flows into a
generic BPM engine would create the opposite problem: approval mechanics would become a second
source of truth for the business object and would conflict with ADR-0009.

The reusable part is the approval decision process itself, not the consuming domain lifecycle.

## Decision

Governance owns a reusable, typed approval-orchestration kernel.

The kernel owns:

- immutable `ApprovalPlan` content after creation;
- ordered sequential `ApprovalStage` snapshots;
- stage decision mode `ANY_ONE` or `ALL`;
- resolved governed approver Identity snapshots;
- an immutable consumer-supplied approval-requirements fingerprint used to detect material policy/context changes without storing arbitrary consumer payloads;
- immutable `ApprovalDecision` evidence;
- current plan/stage outcome and optimistic revision;
- deadline/expiry semantics;
- idempotent/revision-aware decision acceptance;
- queryable pending-approval/inbox state.

An `ApprovalPlan` refers to its consumer through a typed `ApprovalSubject`:
`subjectKind + subjectId`. The initial implemented subject kind is
`ACCESS_REQUEST_ITEM`. Additional consumers extend the typed subject-kind contract and adapter
set; they do not store arbitrary workflow scripts or JSON domain payloads in the approval kernel.

Consuming capabilities/processes retain their own authoritative business state. They decide:

- why approval is required;
- eligibility and policy/risk/SoD evaluation;
- how approvers are resolved before the immutable plan is created;
- what business transition follows APPROVED, REJECTED or EXPIRED;
- what downstream semantic command is issued.

The approval kernel returns/records approval outcomes; it never directly mutates Catalog, Access,
Administration, Credential or another capability repository.

Initial approval execution is deliberately constrained to sequential stages. A stage is complete
when:

- `ANY_ONE`: one assigned participant records APPROVE; any participant REJECT rejects the plan;
- `ALL`: every assigned participant records APPROVE; any participant REJECT rejects the plan.

Later stages are not actionable until all earlier stages approve. Decisions are append-only and a
participant may record at most one decision per plan stage. Completed, rejected, expired or
superseded plans accept no new decisions.

Plan replacement is explicit: material dependency changes create a new plan rather than mutating
historical plan content or decision evidence. Consumers re-resolve requirements before final
authorization and compare the typed structure plus immutable requirements fingerprint; an older
APPROVED plan may remain valid historical evidence while a successor plan becomes the current
requirement for the business subject.

Deadlines are semantic. A plan is expired by time comparison even if a timeout materializer is
late. Timeout never implies approval.

## Cross-capability application

The first consumer is Governance-owned AccessRequest/RequestItem. After an item becomes
`AUTHORIZED`, Governance issues the framework-neutral `AccessIntentCommand`. Access alone
creates the authoritative AccessAssignment.

The request-to-access handoff is causally idempotent by RequestItem ID. AccessAssignment records
`REQUEST_ITEM` provenance and enforces one request-derived assignment per RequestItem. If Access
succeeds and Governance fails before recording `APPLIED`, retrying the command returns the same
assignment when the semantic request matches.

`AUTHORIZED` and `APPLIED` remain separate. Neither means provider provisioning succeeded.

## Consequences

- approval mechanics can be reused by multiple IAM flows without a mandatory BPM engine;
- business lifecycles remain owned by their canonical capability/process;
- approval plans and decisions remain understandable independent of scheduler/workflow vendor;
- consumer-specific policy and approver resolution remain typed and replaceable;
- cross-capability side effects use semantic commands and idempotent forward recovery;
- arbitrary approval DAGs, scripting and generic payload bags remain outside the core model.
