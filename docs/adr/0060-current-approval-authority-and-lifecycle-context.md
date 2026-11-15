# ADR-0060: Current approval authority and material lifecycle context

Status: Accepted

## Context

ADR-0016, ADR-0018, ADR-0028 and ADR-0058 establish immutable approval evidence, scoped exceptions and current request admission. Reusing an approval solely by rule/target/policy version can authorize access with changed material context. Allowing an exception requester to choose their own approval plan undermines independent authority.

## Decision

New approval decisions require the authenticated resolved stage approver to be a currently access-eligible Identity, in addition to stage membership and the case revision. Request and exception beneficiaries cannot decide their own cases; lifecycle candidate beneficiaries cannot decide their own cases. Administrative elevation retains its owner-defined independent approver resolver. Historical immutable decision replay does not create a new decision.

Lifecycle approval candidates are keyed by tenant, identity, lifecycle rule, target, active lifecycle policy version, Identity access eligibility epoch, Governance policy version, target fingerprint, relevant SoD conflict/exception fingerprint and approval-plan hash. Current lifecycle predicates must still match and the current mandatory evaluation must authorize or satisfy the exact candidate. The Access owner revalidates epoch and target fingerprint at creation within ADR-0059 admission serialization. V27 leaves older candidate evidence readable but ineligible for new access. No generic policy engine or additional authority is introduced.

Exception approval is selected by the active SoD rule's policy version, not the caller. The existing request approvalPlan field is a compatibility assertion; its canonical hash must equal the policy-owned plan. The plan may be configured on a policy with SoD rules even when request decisions never require approval. No configured plan means no exception admission. The plan excludes requester and beneficiary. Final approval revalidates the active rule version and exact plan, rejecting obsolete or mismatched candidates without deleting approval evidence. A separate exception-specific policy type is not introduced; policy owners must configure an independently authorized plan. Existing effective exceptions retain their original scoped temporal authority.

## Consequences

Policy or identity changes cannot silently reuse stale approval evidence. Review of a case is not Access mutation. The lifecycle candidate context is material, not a generic Identity revision: repeated evaluation of the same currently matching predicate and unchanged context may reuse the same historical approval, unless an owner-defined reduction or suppression prevents admission. No provider operations run in approval/admission transactions. Future distinct exception plan models require a separate typed decision.
