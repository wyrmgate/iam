# ADR-0017: Versioned Governance policy, risk and SoD evaluation

Status: Accepted

## Context

Wyrmgate IAM already has a durable AccessRequest evaluation boundary and a reusable
ApprovalCase engine. Privilege-increasing RequestItems fail closed when no mandatory
eligibility evaluator is available, but the production runtime does not yet have a
concrete policy/risk/SoD evaluator.

The formal v0.3 baseline requires:

- risk to explain severity and contributing factors while policy decides the action;
- SoD evaluation before privilege increase and during observed-access governance;
- mandatory governance-evaluation failure to fail closed or remain pending;
- immutable approval/evaluation evidence;
- PolicyVersion content to be immutable after activation;
- approval to be revalidated against material current context before final authorization.

A generic expression language or arbitrary JSON policy model would weaken the typed IAM
semantics and create a second generic rules platform inside Governance.

## Decision

Governance owns a typed `Policy` aggregate with immutable versioned content.

### Initial policy purpose

The first runtime policy purpose is `ACCESS_REQUEST`.

There is one stable Policy identity per tenant and purpose. A Policy may have multiple
PolicyVersions, with at most one ACTIVE version. PolicyVersion lifecycle is:

```text
DRAFT -> READY -> ACTIVE -> SUPERSEDED
   \-> CANCELLED
```

Activated or superseded content is immutable. Rollback or amendment creates a new
PolicyVersion.

### Typed v1 policy content

An ACCESS_REQUEST PolicyVersion contains:

- a default decision: `AUTHORIZE`, `REQUIRE_APPROVAL` or `DENY`;
- zero or more symmetric Entitlement-pair `SoDRule` objects;
- an immutable typed approval plan when any path may require approval.

A SoDRule contains:

- two distinct Catalog Entitlement stable IDs;
- severity: `LOW`, `MEDIUM`, `HIGH` or `CRITICAL`;
- enforcement action: `REQUIRE_APPROVAL` or `DENY`.

The canonical pair is stored in deterministic UUID order so the same symmetric pair
cannot be declared twice in one PolicyVersion.

The first approval-plan representation uses explicit immutable stages with
`ANY_ONE`/`ALL` decision mode and explicit governed Identity approver IDs. Dynamic
manager/owner/selector rules are deferred.

No generic expression language, script engine, EAV rules table or arbitrary JSON policy
payload is introduced.

### Evaluation inputs

For each RequestItem, Governance evaluates the current ACTIVE ACCESS_REQUEST PolicyVersion
against:

- the requested target;
- current Catalog Role expansion when the target is a Role;
- the other original items in the same AccessRequest;
- current semantically effective access for the beneficiary, queried through an
  Access-owned semantic query.

Governance never reads Catalog or Access repositories/tables directly.

Role targets are expanded through `RoleExpansionQuery`. Current access is queried in a
bounded way only for counterpart Entitlement IDs referenced by rules relevant to the
requested set.

Requested-vs-requested SoD evaluation is based on the AccessRequest's original item intent
rather than item processing order. A request containing both sides of a deny rule is
therefore deterministic even if one item has already reached a terminal workflow state.

### Evidence and decision separation

Each completed evaluation writes immutable Governance evidence:

- `RiskAssessment` — overall severity and number of contributing factors;
- `SoDConflict` — one evidence row per matched rule/context;
- `PolicyEvaluation` — PolicyVersion, RequestItem revision, decision and stable code.

Risk explains the factors and severity. Policy determines the action.

Decision strength is:

```text
DENY > REQUIRE_APPROVAL > AUTHORIZE
```

The effective action is the stronger of the PolicyVersion default decision and every
matched SoDRule action.

Missing active policy, unavailable required query dependency, or unexpected evaluation
failure yields `UNAVAILABLE`; the RequestItem remains/re-enters `EVALUATING` and is
retried. Deterministically invalid requested Catalog targets are denied rather than
treated as an infrastructure outage.

### Approval revalidation

ApprovalCase remains approval-process state only.

When the final ApprovalCase decision becomes APPROVED, the AccessRequest subject flow
re-runs the current policy/risk/SoD evaluator before changing the RequestItem to
AUTHORIZED.

The result is handled as follows:

- `AUTHORIZE` -> RequestItem becomes AUTHORIZED;
- `DENY` -> RequestItem becomes DENIED;
- `UNAVAILABLE` -> RequestItem becomes EVALUATING and durable evaluation retry work is
  queued;
- `REQUIRE_APPROVAL` with the same approval-plan fingerprint as the completed case ->
  the completed approval satisfies the current requirement and the item becomes
  AUTHORIZED;
- `REQUIRE_APPROVAL` with a different approval-plan fingerprint -> a new ApprovalCase is
  created and the RequestItem remains PENDING_APPROVAL.

Thus a stale approval never authorizes access merely because an earlier approval case
completed.

### GovernanceException

GovernanceException is intentionally not a flag on PolicyEvaluation or SoDRule.

The next bounded phase will add the explicit scoped/time-bound GovernanceException
lifecycle. An exception may affect policy handling only through a typed governed
relationship; it will never delete a SoDConflict or other underlying violation evidence.

## Consequences

- AccessRequest evaluation becomes production-capable instead of depending on a test-only
  evaluator.
- Policy and SoD content is strongly typed and reviewable.
- Evaluation evidence is immutable and explainable.
- SoD checks remain bounded even for large EffectiveAccess populations.
- Approval cannot authorize stale policy/access context.
- Policy changes require new immutable versions.
- Static explicit approvers are sufficient for v1; dynamic approver resolution remains a
  later typed extension.
- The same policy/risk/SoD semantics can later be reused by observed-access and review
  flows without moving authoritative state between capabilities.
