# ADR-0080 Provisioning provider-effect recovery

Status: Accepted

A provider success received with the exact expired lease identity from a still-authenticated worker is immutable attempt evidence, not a successful current task transition. Accept only success without reconciliation fields; keep the task BLOCKED, never re-lease it, and retain exact fingerprint replay. Late failures and mismatched leases remain rejected. Record a durable recovery item for every successful grant mutation and every accepted late success, atomically with the attempt.

Bounded recovery reads current Access technical demand and exact mapped route. A confirmed ADD with no current demand gets a new corrective REMOVE; a confirmed REMOVE with current demand gets a new corrective ADD only with the exact current cause and unique route. New plans have a causal key from the attempt and current desired revision and use the existing current admission checks. Never use a historical provider identifier after the pinned binding/configuration changes; conflicting mappings, missing causes, obsolete routes requiring replacement, unknown principal effects and unresolved correlation become MANUAL, with the original evidence retained. Unavailable desired reads are retried with a bound. Recovery records do not assert provider completion of corrective work. No automatic adoption, arbitrary retry or mutation of authoritative Access/Identity.

Uncertain tasks with no provider success remain blocked; explicit operator evidence/reconciliation is required before a new plan. A late success is not evidence that an already superseded lease may mutate. Provider-specific verification of uncertain creates remains a separate guarded recovery path.
