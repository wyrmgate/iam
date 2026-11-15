# ADR-0073: Provisioning lease uncertainty and serialized completion

Status: Accepted

## Decision

A lost or expired mutation lease is not proof that the external operation did not execute. Until a connector has an explicit safe replay/verification contract, expired RUNNING provisioning tasks transition to existing BLOCKED with normalized reason provider_effect_unknown_after_lease_expiry. Do not blindly reclaim or fabricate an attempt result. Preserve lease identity, operation ID and task payload for later reconciliation/explicit remediation. Bounded sweep of at most 100 tasks per tick, with task row locking and lease recheck, applies to local and remote execution. Reconciliation leases remain reclaimable under existing read-only semantics; coverage continuity is separately hardened.

Completion and observation append serialize on the owning task/run row before checking the current lease. Lease renewal locks only the lease; completion and expiry handling acquire task/run then lease. An exact duplicate completion for the still-current lease remains idempotent; conflicting/stale completions reject. The recorded provider outcome is never rewritten by retry policy. At five completed retryable attempts, the task becomes BLOCKED with retry_budget_exhausted, preserving immutable reported attempts. No new task status or wire outcome is introduced.

## Consequences

BLOCKED is visible through existing operational reads. Explicit recovery commands, verified actual-effect resolution, stale late-effect convergence and safe retry contracts are required in subsequent #524 slices. This change prevents stranded RUNNING work and blind replay; it is not a complete remediation workflow or an assertion of exactly-once provider effects.
