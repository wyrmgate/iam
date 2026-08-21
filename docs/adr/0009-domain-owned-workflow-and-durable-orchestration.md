# ADR-0009: Domain-owned workflow and durable orchestration

Status: Accepted

## Context

IAM contains many long-running processes: approval, review, provisioning, reconciliation, identity merge/split, credential rotation, retirement/decommissioning and scheduled lifecycle work. A generic workflow engine can provide timers and task routing, but if it owns business state it creates a second source of truth and couples IAM semantics to one execution product.

## Decision

Business state machines remain owned by their canonical capabilities. Long-running business processes use durable domain/process records owned by the relevant capability. Generic scheduler/worker infrastructure provides timers, leasing, retry delivery and work claiming but never directly mutates business lifecycle state.

The initial IAM v2 implementation will not require a general-purpose BPM/workflow engine. Approval and review use constrained typed plans rather than arbitrary BPM DAGs. Provisioning, reconciliation, merge/split and credential rotation retain their own typed process models.

Retries assume duplicate delivery and revalidate current state before side effects. Time validity is semantic and does not depend on scheduler punctuality. Downstream technical failure does not roll back a valid upstream governance decision. Long-running cross-capability operations use forward recovery/compensation and semantic commands/facts rather than distributed transaction rollback.

A future workflow engine may be introduced only as an adapter for operational value. It must not become the canonical source for RequestItem, ReviewItem, AccessAssignment, Credential or other domain state.

## Consequences

- Process semantics remain portable across scheduler/workflow technologies.
- PostgreSQL-backed process/timer/task records are sufficient initially.
- Timers and retries are at-least-once and idempotent/revision-aware.
- Workflow history/evidence remains understandable without a vendor engine database.
- Cross-capability retirement/decommissioning is coordinated explicitly rather than through cascade delete.
