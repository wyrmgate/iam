# ADR-0083 Verified provisioning recovery decisions

Status: Accepted

## Decision

Integration owns an immutable recovery decision separate from the original task and provider attempt. An authorized operator attests that prior external execution has quiesced, then runs reconciliation; lease expiry is not proof of quiescence. The explicit recovery command consumes the exact blocked task revision and a current finalized COMPLETE, FULL, first-lease scan for its same pinned binding, configuration and object class, started after quiescence. Scan generation and route remain fenced. No newer accepted scan, live competing plan or automatic effect recovery decision may be overridden. A unique task decision and causal request idempotency prevent duplicate plans; mandatory data-minimized Audit evidence commits with the decision.

Integration derives actual grant presence by exact provider pair and current Access technical demand. A mismatch may create a distinct current-revision corrective plan for the exact route, subject to normal admission on claim and execution. Existing account effects use an explicit current Principal mapping, Identity-owned current governed reference, and exact active/inactive provider observation. Unknown create requires positive exact mapped account evidence (and agreement with late provider success when present); no username/externalId correlation, invented success, inferred absence of an unidentified account or blind creation replay. When prerequisites cannot be established, remain BLOCKED and investigate through provider-side evidence and governed Identity/mapping operations. Changed historical routes are not repaired using stale provider identifiers.

MATCHES_DESIRED means observation at the verified scan matched current technical demand. CORRECTIVE_PLAN_CREATED means a new causal plan exists, not provider completion. Neither changes the original BLOCKED task, attempt evidence, AccessAssignment or Identity authority. Any corrective task that later becomes uncertain is itself eligible for recovery. Late provider success remains immutable evidence and is processed under ADR-0080; an operator must actually stop in-flight execution before verifying. No distributed rollback, generic status mutation or execution outside normal provider fences.

## Consequences

Dedicated default-deny task recovery authority and evidence-run read authority. No raw provider identifiers or secrets in recovery command/result. The correction uses existing provider adapter contracts; no provider I/O in the authoritative transaction. The bounded supported profile is exact grant effects and mapped principal activation effects; unresolved creates remain blocked until independent exact correlation is established.
