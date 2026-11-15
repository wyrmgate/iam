# ADR-0058: Material RequestItem authorization and causal application

Status: Accepted (GOV-V3-02a). Extends ADR-0017 and ADR-0055. The separate concurrent
SoD admission design remains proposed in GOV-V3-03.

## Decision

A positive request evaluation snapshots a typed immutable context: Identity
eligibility epoch, active PolicyVersion ID, exact Catalog target fingerprint
(Entitlement/target or sorted RoleVersion derivation paths), matched SoD and
exception-context fingerprint, and the PolicyEvaluation ID. Governance records it
with the resulting RequestItem revision and ApprovalCase reference. Approval
reuse requires both the original material context and the immutable plan hash.
The current policy decision always wins. An outage preserves the completed
ApprovalCase and retries evaluation rather than requiring a new human decision
for unchanged context. A changed PolicyVersion, Role expansion or relevant
conflict/exception context requiring approval starts a successor case.

A queued AUTHORIZED item is permission to attempt, not irrevocable authority.
Before new Access application, Governance checks causal Access result by exact
immutable request intent. A historical committed result is recovered without
reinterpretation under a later policy. Otherwise it evaluates current context.
A positive changed context either republishes a new authorization revision or
requires a new approval; current DENY denies, unavailable evaluation retries,
ended validity expires, and a stale Identity epoch cancels. Old pending/authorized
items without historical context cannot create new access (cancel and resubmit);
existing applied history remains intact.

Access receives the expected Identity epoch and target fingerprint and compares
them to its own current admission reads. Its existing epoch guard prevents
cross-era revival even when Identity changes after the check. Current Principal
compatibility, Catalog availability and validity checks remain Access-owned.
Governance never directly mutates assignments; provider fulfillment remains
outside authoritative transactions.

## Scope and limitations

V25 adds append-only Governance request_admission_context; it does not invent
a cross-capability authority or backfill unknown historical evidence. Context
links retain the immutable policy/evaluation identifiers. The current
PolicyEvaluation and SoD evidence already record exact rules/exception IDs;
the fingerprint is canonical sorted conflict evidence and does not include
unrelated display/profile revision. Current mandatory revalidation and Access
target/epoch fencing do not by themselves serialize concurrent independent
admissions or order every Policy/Catalog mutation against commit. GOV-V3-03
must introduce the Access-owned concurrency boundary and authoritative intent
query, including scheduled-window overlap. This ADR does not change manual,
resume or lifecycle admission policy, nor add a global continuous SoD guarantee.
