# ADR-0025: Access-owned lifecycle policy and governed Joiner/Mover reconciliation

Status: Accepted

## Context

The controlled v0.4 baseline defines the Joiner flow as source correlation/canonical resolution,
lifecycle policy, assignments, desired state and provisioning. It defines the Mover flow as an
authoritative canonical fact change followed by lifecycle/policy reevaluation and an access delta.

ADR-0021 through ADR-0024 now provide Identity access eligibility, source-driven Identity
construction, explicit lifecycle mapping and safe negative/absence handling. The missing decision
is how baseline/birthright access and Mover deltas become authoritative AccessAssignment intent
without moving Access authority into Identity or Governance, bypassing mandatory SoD controls, or
making provider state authoritative.

## Decision

### Access owns lifecycle-access policy and assignment reconciliation

LifecycleAccessPolicyVersion is Access-owned authoritative policy because its effect is the
creation/removal of Access-owned AccessAssignment intent.

There is at most one ACTIVE immutable policy version per tenant. Activating a new version
supersedes the prior version. The first version supports at most 100 rules.

A rule has a stable logical rule UUID, reusable across policy versions. That rule UUID is the
causal provenance for policy-created AccessAssignments.

The first typed rule shape is deliberately bounded:

- predicate `ALWAYS`; or
- exact equality on one active policy-addressable canonical `STRING/SINGLE` attribute;
- target `ROLE` or `ENTITLEMENT`;
- principal constraint fixed to `ANY`.

No script engine, arbitrary JSON policy body, generic expression language or EAV rules model is
introduced.

### Identity exposes current policy inputs through a semantic query

Access never reads Identity or canonical-attribute persistence.

Identity exposes a narrow IdentityLifecycleAccessQuery that returns:

- current Identity lifecycle/revision;
- only the requested policy-addressable canonical STRING/SINGLE values;
- a trusted value only when current canonical state is RESOLVED or OVERRIDDEN.

CONFLICT, UNRESOLVED, NO_VALUE, missing or shape-incompatible canonical state cannot satisfy a
positive rule.

Only ACTIVE Identity is eligible for lifecycle-policy grants.

### Dedicated internal facts trigger current-state reconciliation

Identity emits a dedicated internal `identity.lifecycle-access-input-changed` fact after:

- Identity creation;
- Identity lifecycle change; and
- canonical attribute state change.

This event type is dedicated to Access reconciliation so it does not compete with another outbox
consumer for the same internal fact.

The event is a trigger, not authority. Access always re-reads the current active policy and current
Identity context. Duplicate, replayed and out-of-order triggers therefore converge on current
state.

Policy activation itself does not perform a tenant-wide retroactive sweep in the first slice.
Large retroactive reevaluation requires a separate bounded durable operation.

### Stable policy provenance owns only its own assignment

AccessAssignment gains provenance kind `LIFECYCLE_POLICY_RULE`.

Its provenance reference is the stable logical lifecycle-policy rule UUID. At most one non-terminal
assignment may exist for one tenant + Identity + rule UUID.

A policy version may reuse a rule UUID. If the rule still resolves to the same target, the existing
assignment remains authoritative and is not recreated.

If the rule no longer matches, disappears, or changes target, Access terminates the existing
non-terminal policy assignment. If the rule currently matches a different target, the old
assignment is terminated before any attempt to create the new privilege.

Terminal historical assignments remain history. A later matching rule may create a new assignment
for the same logical rule after the prior assignment is terminal.

Manual and REQUEST_ITEM assignments are never terminated merely because lifecycle policy support
disappears.

### Reductions are evaluator-independent; increases are guarded

Reconciliation always applies policy-owned reductions before evaluating any new privilege.

Removing a policy-created assignment does not depend on Governance evaluator availability. This
preserves the fail-open-for-reduction requirement from SRS-GOV-002.

Every new lifecycle-policy assignment is a privilege increase and must pass the mandatory
LifecycleAccessPrivilegeGuard.

The guard is an Access-consumer-defined semantic contract implemented by Governance. Access does
not read Governance persistence.

The first Governance implementation reuses the current ACTIVE ACCESS_REQUEST PolicyVersion's typed
SoD rules as the mandatory SoD control set, consistent with ADR-0017's direction that the same
policy/risk/SoD semantics may be reused by other governed flows.

The ACCESS_REQUEST default decision is not reused: the lifecycle-access rule is itself the
authorization source. The guard evaluates only mandatory SoD constraints for the candidate target.

For a candidate target:

- Entitlement is evaluated directly;
- Role is expanded through current Catalog role expansion;
- current EffectiveAccess is queried only for counterpart Entitlements relevant to active SoD
  rules;
- SoD conflicts entirely inside the candidate Role expansion are also evaluated;
- exact effective GovernanceException coverage may waive only its covered SoDRule.

The guard returns:

- `AUTHORIZE` when no uncovered conflict remains;
- `DENY` when an uncovered DENY rule matches;
- `REQUIRE_APPROVAL` when an uncovered approval rule matches;
- `UNAVAILABLE` when the active mandatory governance policy or a required dependency is
  unavailable.

Only AUTHORIZE creates an automatic assignment.

Every completed guard attempt records immutable Governance lifecycle-access evaluation evidence:
the candidate Identity/rule/target, current Governance PolicyVersion when available, decision/code,
matched SoD rules, exact GovernanceException coverage, evaluated time and end-to-end
correlation/causation. Evaluation evidence is not AccessAssignment authority.

REQUIRE_APPROVAL does not create an approval case in this first slice; it leaves the automatic
assignment absent. UNAVAILABLE fails closed and the Access trigger is retried.

### Desired state and provider fulfillment remain downstream

Lifecycle policy mutates only AccessAssignment authority through ordinary Access commands.

EffectiveAccess, desired Principal/Grant state and Integration provisioning/reconciliation continue
through their existing asynchronous projection/planning paths. No provider call occurs inside
lifecycle-policy reconciliation.

## Consequences

- Source-driven Joiners can receive baseline/birthright access after becoming ACTIVE.
- Canonical Mover changes produce policy-owned access deltas without Identity mutating Access.
- Stable rule provenance makes replay and policy-version replacement idempotent.
- Policy reductions proceed even when Governance is unavailable.
- Automatic privilege increases remain fail closed under mandatory Governance dependency failure.
- Role and Entitlement targets reuse current Catalog and Access validation.
- Existing manual/request authority is independent of birthright support.

## Deferred

- approval workflow for lifecycle rules whose mandatory guard returns REQUIRE_APPROVAL;
- SPECIFIC Principal lifecycle-policy grants;
- temporal lifecycle-policy grants;
- compound/range/multi-valued predicates;
- tenant-wide retroactive policy activation/re-evaluation operation;
- dynamic manager/organization/ownership predicates;
- Identity merge/split;
- generic BPM/rules-engine infrastructure.
