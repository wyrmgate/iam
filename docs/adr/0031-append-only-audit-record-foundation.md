# ADR-0031: Append-only AuditRecord foundation and replay semantics

Status: Accepted

## Context

The v0.5 formal baseline requires trustworthy audit/evidence sufficient to reconstruct important
decisions and security-relevant actions. Audit is a distinct owning capability: domain facts,
AuditRecord, EvidenceSnapshot and operational logs have different semantics. The repository
physical data model already defines the first audit.audit_record shape, but runtime Audit ownership,
normalized outcomes and retry behavior are not yet implemented.

Audit producers may run under at-least-once delivery or retryable control-plane flows. Audit must
therefore avoid duplicate evidence without treating occurrence or recording time as a mutable
business object.

## Decision

### Audit owns immutable AuditRecord

The first AuditRecord contains:

- stable record ID;
- occurredAt and recordedAt;
- optional governed actor Identity ID;
- semantic actionType;
- semantic resourceType plus optional resource ID;
- normalized outcome;
- optional correlationId and causationId.

The first normalized outcomes are SUCCESS, DENIED and FAILURE.

AuditRecord is immutable evidence. It is never current business-state authority and cannot be
updated or deleted through ordinary domain operations.

### Append uses stable caller-supplied record identity

Producers call a narrow Audit-owned append command/port. The producer supplies the stable AuditRecord
ID and semantic occurrence context. Audit supplies/persists recordedAt.

Replay of the same record ID with the same semantic content is idempotent and returns the existing
record. recordedAt is not compared for replay identity because a retry may occur later.

Reuse of the same record ID with different actor, action, resource, outcome, occurrence time,
correlation or causation is a conflict and is never silently overwritten.

### Query is bounded and deterministic

Audit exposes an application query ordered by occurredAt DESC then record ID DESC. The first query
supports optional exact filters for actor ID, action type, resource type, resource ID, outcome and
correlation ID.

Pagination is keyset-based and bounded. Audit search does not require loading unbounded evidence or
joining foreign capability repositories.

### Persistence follows the accepted physical model

V46 creates audit.audit_record with the fields already defined in
docs/architecture/physical-data-model.md. material_snapshot and integrity_metadata remain nullable
and unused in this first slice.

No foreign-capability database foreign key is added for actor/resource IDs. Tenant isolation is
enforced by every Audit repository operation.

### Secret and evidence boundaries remain strict

Audit action/resource metadata must be data-minimized. Raw passwords, private keys, tokens,
connector secrets and equivalent secret/private material are prohibited.

Material snapshots, EvidenceSnapshot, integrity-chain metadata, archive/retention execution and
external SIEM/export transport are separate later decisions. Existing ApprovalDecision,
ReviewDecision, ProvisioningAttempt and other capability-owned evidence are not copied into
AuditRecord merely to centralize storage.

## Consequences

- Wyrmgate gains a real Audit-owned append-only evidence foundation without event sourcing;
- at-least-once/retry producers can append safely using stable record identity;
- bounded queries can support later API/search/export surfaces;
- foreign capability authority remains separate from Audit evidence;
- producer adoption can proceed incrementally through a semantic command rather than Audit table
  access.

## Implementation status

The foundation is implemented through Flyway V46 and the Audit-owned append/query runtime.

The first public Audit control-plane slice is also implemented:

- `GET /api/v1/audit-records` provides bounded exact-filter search;
- `GET /api/v1/audit-records/{auditRecordId}` provides tenant-scoped detail reads;
- both require default-deny `audit:read` authorization;
- collection continuation uses signed tenant/filter-bound, time-bounded cursors.

Producer adoption now covers the current public mutation surfaces for Identity create/display-name update/lifecycle/merge/split, Principal register/correlate, Catalog Application/ApplicationTarget/Entitlement, Catalog Role/RoleVersion, AccessAssignment, Credential, Governance AccessRequest/approval/review, and Integration connector/binding/worker plus entitlement-observation mapping administration. These producers append data-minimized SUCCESS/DENIED/FAILURE evidence only after the authoritative transaction commits or rolls back, and Audit persistence failure does not rewrite the business outcome.

This implementation status does not expand AuditRecord authority or change the accepted replay, query, tenant-isolation, or secret-boundary semantics above.

## Deferred

- audit export/download and any `audit:export` authorization surface;
- archive/retention execution;
- EvidenceSnapshot runtime;
- material snapshots and integrity metadata;
- future producer adoption for later public/high-impact operations not yet implemented;
- SIEM/notification transports.
