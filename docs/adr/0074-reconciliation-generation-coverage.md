# ADR-0074: Reconciliation generation and full-scan evidence fence

Status: Accepted

## Decision

V34 assigns a monotonic scan generation and pins the current ConnectorBinding/ConnectorInstance revisions on first lease. Historical completed rows retain no fabricated generation; a new scan supersedes that baseline. Serialize finalization by locking binding and connector. Older generation completion after newer finalized generation, or incompatible binding/configuration/runtime/contract revision, retains run outcome but never updates current observations or coverage. No provider call occurs in this transaction.

Effective COMPLETE requires the existing trusted supported discovery capability plus a first-lease full traversal (null initial and final checkpoint), contiguous zero-based persisted batch sequences and the adapter's explicit integer observationBatchCount in bounded completion metadata matching the persisted count. Zero batches require an explicit zero count. Missing count, gap, resumed/reclaimed run, non-null checkpoint or stale scope cannot infer absence. Positive observations remain usable for current compatible scope even when completeness is UNKNOWN/PARTIAL. Remote v1 core remains unchanged: older adapters without this metadata retain positive observation support but cannot claim destructive completeness. This is a necessary transport/coverage fence, not a substitute for provider-specific complete traversal verification.

SCIM local reports the batch count and permits PRINCIPAL discovery through the exact group binding's configured SCIM Users endpoint; this supplies account observations for explicit mapping without cross-binding guessing.

## Consequences

The first safety tranche still uses existing completion materialization; durable bounded materialization and exact discovery-scope scheduling remain follow-up. No filtered scope gains full-scope absence authority. Future provider contracts must prove their traversal semantics and configuration scope.
