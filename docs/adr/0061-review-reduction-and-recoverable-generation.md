# ADR-0061: Review reduction and recoverable review work

Status: Accepted

## Decision

A REVOKE decision remains immutable Governance evidence. Access applies the causal remediation against current authority. For a lifecycle-derived assignment, it atomically records an Access-owned suppression of the exact tenant/Identity, eligibility epoch, logical rule and target. The lifecycle reconciler checks this suppression under the same local admission fence before increasing privilege. Review remediation uses that fence only for the lifecycle cause reduction, not Governance evaluation. If the reviewed assignment was already removed and an exact same-cause successor exists, the reduction may terminate that successor. Different epochs, rules, targets and unrelated request/manual authority are not suppressed. A new explicit rule identity or later eligibility epoch constitutes new governance intent and must pass current admission. No generic exception/waiver is introduced.

Review generation's snapshotAt is a creation/validity selection cutoff, not a temporal reconstruction of old assignment revisions. Each review item retains its observed authoritative assignment revision, state, provenance, target, validity and creation time; the item creation timestamp is its observation timestamp.

Valid review work is revision fenced. Malformed envelopes are terminal technical failures. Repeated failed generation/remediation work transitions to a durable visible failure state after a bounded retry budget, without losing immutable decisions or completed generation pages. Authorized retry preserves the original campaign cutoff/checkpoint or the remediation causal identity, and emits a new revision-bound fact. A stale delivery cannot mutate a recovered aggregate.

## Consequences

Review reduction remains independent of Governance policy availability. The Access suppression is scoped and durable, not a cross-capability write. Generation completion is distinct from remediation and technical provider convergence.
